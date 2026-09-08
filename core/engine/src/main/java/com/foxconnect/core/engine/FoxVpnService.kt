package com.foxconnect.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.foxconnect.core.model.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ServiceStartRequest { CONNECT, RECOVER, DISCONNECT, IGNORE }
internal const val MAX_FAILOVER_ATTEMPTS_PER_ROUND = 4

internal fun classifyServiceStart(
    action: String?,
    authorizedSystemRecovery: Boolean = false,
): ServiceStartRequest = when {
    action == FoxVpnService.ACTION_CONNECT -> ServiceStartRequest.CONNECT
    action == FoxVpnService.ACTION_DISCONNECT -> ServiceStartRequest.DISCONNECT
    action == null && authorizedSystemRecovery -> ServiceStartRequest.RECOVER
    else -> ServiceStartRequest.IGNORE
}

internal object CoreFailureClassifier {
    fun classify(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val messages = chain.mapNotNull { it.message?.lowercase(java.util.Locale.ROOT) }.joinToString(" ")
        val stage = chain.filterIsInstance<CoreStartFailure>().firstOrNull()?.stage
        return when {
            "health_verification_failed" in messages ||
                "native_traffic_not_observed" in messages -> "tunnel_verification_failed"
            "vpn_permission" in messages || "permission_revoked" in messages -> "vpn_permission_revoked"
            "tun_" in messages || "opentun" in messages || "builder.establish" in messages -> "tun_establish_failed"
            "protect_outbound_socket_failed" in messages ||
                "socket_protection_not_observed" in messages -> "socket_protection_failed"
            "physical_network_not_observed" in messages -> "core_network_monitor_failed"
            stage == CoreStartStage.CONFIG_CHECK || "checkconfig" in messages ||
                "check config" in messages || "parse config" in messages -> "native_config_rejected"
            stage == CoreStartStage.VERSION || "version_mismatch" in messages -> "libbox_version_mismatch"
            stage == CoreStartStage.SETUP -> "core_setup_failed"
            stage == CoreStartStage.COMMAND_CREATE || stage == CoreStartStage.COMMAND_START -> "core_command_failed"
            stage == CoreStartStage.NETWORK_MONITOR -> "core_network_monitor_failed"
            stage == CoreStartStage.SERVICE_START -> "core_service_start_failed"
            stage == CoreStartStage.POST_START -> "core_post_start_failed"
            else -> "native_start_failed"
        }
    }
}

class FoxVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val store by lazy { EngineConfigStore(this) }
    private val healthStore by lazy { ProfileHealthStore(this) }
    private val eventLog by lazy { TunnelEventLog(this) }
    private val healthVerifier by lazy { TunnelHealthVerifier() }
    private val runtimePublisher by lazy { TunnelStatePublisher(this) }
    private val authorization by lazy { TunnelRunAuthorization(this) }
    private val recoveryRateLimiter by lazy { ServiceRecoveryRateLimiter(this) }
    private var core: TypedLibboxCore? = null
    private var connectionJob: Job? = null
    private var recoveryJob: Job? = null
    private var statsJob: Job? = null
    private var notificationStatsJob: Job? = null
    private var watchdogJob: Job? = null
    private var heartbeatJob: Job? = null
    private var leakGuardDescriptor: ParcelFileDescriptor? = null
    private var pendingConfig: PendingEngineConfig? = null
    private var failoverPolicy: FailoverPolicy? = null
    private var coreGeneration = 0L
    private var connectedAtEpochMs = 0L
    private var lastQualityPersistAtEpochMs = 0L
    private var lastQualitySwitchAtEpochMs = 0L
    @Volatile private var explicitStop = false
    @Volatile private var lastFailureCode = "native_start_failed"
    @Volatile private var hasVerifiedConnection = false

    override fun attachBaseContext(newBase: Context) {
        val language = newBase.getSharedPreferences(PRODUCT_PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "fa")
            ?.takeIf { it == "fa" || it == "en" }
            ?: "fa"
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(language))
            setLayoutDirection(java.util.Locale.forLanguageTag(language))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val authorizedSystemRecovery = intent == null &&
            authorization.isVerified() &&
            VpnService.prepare(this) == null &&
            runCatching { store.read() != null }.getOrDefault(false) &&
            recoveryRateLimiter.acquire()
        val request = classifyServiceStart(intent?.action, authorizedSystemRecovery)
        if (request == ServiceStartRequest.IGNORE) {
            explicitStop = true
            authorization.revoke()
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }
        return try {
            when (request) {
                ServiceStartRequest.DISCONNECT -> {
                    explicitStop = true
                    runtimePublisher.disconnecting()
                    authorization.revoke()
                    connectionJob?.cancel()
                    recoveryJob?.cancel()
                    watchdogJob?.cancel()
                    heartbeatJob?.cancel()
                    scope.launch { operationMutex.withLock { stopTunnel() } }
                }
                ServiceStartRequest.CONNECT,
                ServiceStartRequest.RECOVER -> {
                    if (!authorization.isAuthorized()) {
                        explicitStop = true
                        runtimePublisher.failed(getString(R.string.vpn_retry_blocked), "unauthorized_connect_blocked")
                        stopSelf(startId)
                        return Service.START_NOT_STICKY
                    }
                    if (request == ServiceStartRequest.CONNECT) recoveryRateLimiter.reset()
                    explicitStop = false
                    startForegroundCompat(buildNotification(getString(R.string.vpn_notification_connecting)))
                    startHeartbeat()
                    if (connectionJob?.isActive != true && recoveryJob?.isActive != true && core == null) {
                        hasVerifiedConnection = request == ServiceStartRequest.RECOVER
                        connectionJob = scope.launch {
                            operationMutex.withLock {
                                startTunnel(recoverContinuously = request == ServiceStartRequest.RECOVER)
                            }
                        }
                    }
                }
                ServiceStartRequest.IGNORE -> Unit // Returned above.
            }
            if (request == ServiceStartRequest.DISCONNECT) Service.START_NOT_STICKY else Service.START_STICKY
        } catch (_: Throwable) {
            explicitStop = true
            authorization.revoke()
            runCatching { closeCoreIntentionally() }
            runCatching { releaseLeakGuard() }
            runtimePublisher.failed(getString(R.string.vpn_start_failed), "foreground_service_start_failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            Service.START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        explicitStop = true
        authorization.revoke()
        log(TunnelEventCode.PERMISSION_REVOKED)
        store.clear()
        scope.launch {
            operationMutex.withLock {
                stopTunnel()
                runtimePublisher.failed(getString(R.string.vpn_permission_revoked), "vpn_permission_revoked")
            }
        }
    }

    override fun onDestroy() {
        explicitStop = true
        watchdogJob?.cancel()
        heartbeatJob?.cancel()
        statsJob?.cancel()
        notificationStatsJob?.cancel()
        connectionJob?.cancel()
        recoveryJob?.cancel()
        closeCoreIntentionally()
        releaseLeakGuard()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startTunnel(recoverContinuously: Boolean = false) {
        if (core != null && TunnelRuntime.snapshot.value.state !is ConnectionState.Failed) return
        val config = store.read()
        if (config == null) {
            fail(getString(R.string.vpn_no_selected_config), "missing_config")
            return
        }
        if (VpnService.prepare(this) != null) {
            fail(getString(R.string.vpn_permission_missing), "vpn_permission_missing")
            return
        }
        pendingConfig = config
        lastFailureCode = "native_start_failed"
        val settings = readFailoverSettings()
        val policy = FailoverPolicy(config.candidates.size, config.activeIndex, settings)
        val now = System.currentTimeMillis()
        val health = healthStore.read(config.candidates.map { it.id })
        config.candidates.forEachIndexed { index, candidate ->
            val metric = health[candidate.id] ?: return@forEachIndexed
            policy.seedCooldown(
                index = index,
                cooldownUntilMs = metric.cooldownUntilEpochMs,
                nowMs = now,
            )
            policy.seedQuality(
                index = index,
                latencyMs = metric.tunnelLatencyMs,
                measuredAtMs = metric.tunnelMeasuredAtEpochMs,
                nowMs = now,
            )
            policy.seedEndpointReachability(
                index = index,
                latencyMs = metric.endpointLatencyMs,
                measuredAtMs = metric.endpointMeasuredAtEpochMs,
                nowMs = now,
            )
        }
        failoverPolicy = policy

        val target = if (recoverContinuously) {
            policy.bestAvailable(nowMs = now, excluded = emptySet(), includeActive = true)
                ?: config.activeIndex
        } else {
            config.activeIndex
        }
        policy.activate(target)
        val connected = runCatching {
            establishCandidate(target, switching = recoverContinuously, settings)
        }.isSuccess
        if (connected) {
            startMonitoring(settings)
            return
        }
        closeCoreIntentionally()
        val failedAt = System.currentTimeMillis()
        policy.markFailed(target, failedAt)
        healthStore.recordFailure(config.candidates[target].id, failedAt + settings.cooldownMs)
        if (!explicitStop && settings.enabled) {
            val recovered = recoverCandidates(
                settings = settings,
                continuous = recoverContinuously,
                failedIndexAlreadyMarked = true,
            )
            if (recovered) {
                startMonitoring(settings)
                return
            }
        }
        if (!explicitStop) {
            fail(failureMessage(lastFailureCode), "selected_candidate_failed_$lastFailureCode")
        }
    }

    private suspend fun establishCandidate(
        index: Int,
        switching: Boolean,
        settings: FailoverSettings,
    ) {
        val config = requireNotNull(pendingConfig)
        val candidate = config.candidates[index]
        notificationStatsJob?.cancel()
        notificationStatsJob = null
        if (switching) {
            log(TunnelEventCode.SWITCHING)
            runtimePublisher.switching(candidate.profileName, candidate.protocol)
            updateNotification(getString(R.string.vpn_notification_switching), candidate.profileName)
        } else {
            log(TunnelEventCode.CONNECTING)
            runtimePublisher.connecting(candidate.profileName, candidate.protocol)
            updateNotification(getString(R.string.vpn_notification_connecting), candidate.profileName)
        }

        if (switching) engageLeakGuard()
        closeCoreIntentionally()
        val generation = ++coreGeneration
        val nextCore = TypedLibboxCore(
            service = this,
            onCoreStopRequested = { handleUnexpectedCoreStop(generation) },
            beforeTunEstablish = { releaseLeakGuard() },
        )
        try {
            nextCore.start(candidate.json)
            core = nextCore
            val verification = healthVerifier.awaitVerified(
                maxAttempts = if (switching) 1 else 2,
                timeoutMs = if (switching) FAILOVER_VERIFICATION_TIMEOUT_MS else INITIAL_VERIFICATION_TIMEOUT_MS,
            )
            if (!verification.successful) error("health_verification_failed")
            awaitDataPathEvidence(nextCore)?.let(::error)
            val verifiedAt = System.currentTimeMillis()
            failoverPolicy?.activate(index)
            failoverPolicy?.recordHealthy(index, verification.latencyMs, verifiedAt)
            pendingConfig = config.withActiveIndex(index)
            store.write(requireNotNull(pendingConfig))
            healthStore.recordTunnelSuccess(candidate.id, verification.latencyMs, verifiedAt)
            connectedAtEpochMs = verifiedAt
            lastQualityPersistAtEpochMs = verifiedAt
            releaseLeakGuard()
            log(TunnelEventCode.VERIFIED)
            hasVerifiedConnection = true
            authorization.markVerified()
            runtimePublisher.verified(
                profileName = candidate.profileName,
                protocol = candidate.protocol,
                switched = switching,
                latencyMs = verification.latencyMs,
            )
            updateNotification(getString(R.string.vpn_notification_connected), candidate.profileName)
            statsJob?.cancel()
            statsJob = scope.launch {
                TunnelStatsMonitor { core?.trafficSnapshot() }.run()
            }
            startNotificationStats(candidate.profileName)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            lastFailureCode = classifyFailure(error)
            logFailure(lastFailureCode)
            if (core === nextCore) core = null
            coreGeneration++
            nextCore.close()
            if (switching) engageLeakGuard()
            throw error
        }
    }

    private suspend fun awaitDataPathEvidence(candidateCore: TypedLibboxCore): String? {
        repeat(DATA_PATH_EVIDENCE_ATTEMPTS) { attempt ->
            val pathFailure = DataPathReadinessVerifier.failureReason(candidateCore.dataPathSnapshot())
            val trafficObserved = DataPathReadinessVerifier.nativeTrafficObserved(candidateCore.trafficSnapshot())
            if (pathFailure == null && trafficObserved) return null
            if (attempt + 1 < DATA_PATH_EVIDENCE_ATTEMPTS) delay(DATA_PATH_EVIDENCE_POLL_MS)
        }
        return DataPathReadinessVerifier.failureReason(candidateCore.dataPathSnapshot())
            ?: "native_traffic_not_observed"
    }

    private fun startNotificationStats(profileName: String) {
        notificationStatsJob?.cancel()
        notificationStatsJob = scope.launch {
            while (isActive && !explicitStop) {
                delay(1_000)
                val snapshot = TunnelRuntime.snapshot.value
                if (snapshot.state !is ConnectionState.Connected) continue
                val stats = snapshot.stats
                val connectedAt = stats.connectedAtEpochMs ?: continue
                val elapsedSeconds = ((System.currentTimeMillis() - connectedAt).coerceAtLeast(0)) / 1_000
                val status = getString(
                    R.string.vpn_notification_live,
                    formatBytes(stats.rxBytes),
                    formatBytes(stats.txBytes),
                    formatDuration(elapsedSeconds),
                )
                updateNotification(status, profileName)
            }
        }
    }

    private fun startMonitoring(settings: FailoverSettings) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var consecutiveFailures = 0
            val qualityGate = QualitySwitchHysteresis(settings.qualityFailureThreshold)
            var lastReturnCheck = System.currentTimeMillis()
            while (isActive && !explicitStop) {
                delay(settings.watchdogIntervalMs)
                val result = healthVerifier.probeTunnel(settings.probeTimeoutMs)
                runtimePublisher.healthChecked(result)
                val now = System.currentTimeMillis()
                if (result.successful) {
                    consecutiveFailures = 0
                    val policy = failoverPolicy
                    val active = policy?.activeIndex
                    if (active != null) {
                        policy.recordHealthy(active, result.latencyMs, now)
                        if (now - lastQualityPersistAtEpochMs >= QUALITY_PERSIST_INTERVAL_MS) {
                            pendingConfig?.candidates?.getOrNull(active)?.let { candidate ->
                                healthStore.recordTunnelSuccess(candidate.id, result.latencyMs, now)
                            }
                            lastQualityPersistAtEpochMs = now
                        }
                    }
                    val stableLongEnough = now - connectedAtEpochMs >= settings.qualityMinimumConnectedMs
                    val outsideSwitchCooldown = now - lastQualitySwitchAtEpochMs >= settings.qualitySwitchCooldownMs
                    val betterAlternative = if (stableLongEnough && outsideSwitchCooldown) {
                        policy?.betterAlternative(now)
                    } else {
                        null
                    }
                    val qualitySwitchTarget = qualityGate.observe(betterAlternative)
                    if (qualitySwitchTarget != null) {
                        log(TunnelEventCode.QUALITY_DEGRADED)
                        val recovered = operationMutex.withLock {
                            if (explicitStop) false else recoverCandidates(
                                settings = settings,
                                continuous = true,
                                failedIndexAlreadyMarked = false,
                            )
                        }
                        if (recovered) lastQualitySwitchAtEpochMs = System.currentTimeMillis()
                    } else if (
                        !settings.qualitySwitchEnabled &&
                        now - lastReturnCheck >= settings.returnCheckIntervalMs
                    ) {
                        lastReturnCheck = now
                        operationMutex.withLock { attemptReturnToPreferred(settings) }
                    }
                } else {
                    qualityGate.observe(null)
                    consecutiveFailures++
                    if (consecutiveFailures >= settings.failureThreshold) {
                        log(TunnelEventCode.HEALTH_FAILED)
                        consecutiveFailures = 0
                        operationMutex.withLock {
                            if (!explicitStop && settings.enabled) {
                                recoverCandidates(
                                    settings = settings,
                                    continuous = true,
                                    failedIndexAlreadyMarked = false,
                                )
                            } else if (!explicitStop) {
                                fail(getString(R.string.vpn_all_profiles_failed), "watchdog_failed_failover_disabled")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun recoverCandidates(
        settings: FailoverSettings,
        continuous: Boolean,
        failedIndexAlreadyMarked: Boolean,
    ): Boolean {
        if (!settings.enabled) return false
        val policy = failoverPolicy ?: return false
        val config = pendingConfig ?: return false
        if (!failedIndexAlreadyMarked) {
            val failedIndex = policy.activeIndex
            val failedAt = System.currentTimeMillis()
            policy.markFailed(failedIndex, failedAt)
            healthStore.recordFailure(config.candidates[failedIndex].id, failedAt + settings.cooldownMs)
        }
        val guarded = engageLeakGuard()
        if (guarded) log(TunnelEventCode.KILL_SWITCH_BLOCKING)
        closeCoreIntentionally()
        statsJob?.cancel()
        statsJob = null
        notificationStatsJob?.cancel()
        notificationStatsJob = null

        var allFailedLogged = false
        while (!explicitStop && authorization.isAuthorized()) {
            val excluded = mutableSetOf<Int>()
            var attempts = 0
            val roundLimit = minOf(MAX_FAILOVER_ATTEMPTS_PER_ROUND, policy.candidateCount)
            while (!explicitStop && attempts < roundLimit) {
                val now = System.currentTimeMillis()
                val target = policy.bestAvailable(
                    nowMs = now,
                    excluded = excluded,
                    includeActive = true,
                ) ?: break
                excluded += target
                attempts++
                policy.activate(target)
                val connected = runCatching {
                    establishCandidate(target, switching = true, settings)
                }.isSuccess
                if (connected) {
                    lastQualitySwitchAtEpochMs = System.currentTimeMillis()
                    return true
                }
                closeCoreIntentionally()
                val targetFailedAt = System.currentTimeMillis()
                policy.markFailed(target, targetFailedAt)
                healthStore.recordFailure(config.candidates[target].id, targetFailedAt + settings.cooldownMs)
            }
            if (!continuous) return false
            val allCandidatesCooling = policy.bestAvailable(
                nowMs = System.currentTimeMillis(),
                excluded = emptySet(),
                includeActive = true,
            ) == null
            if (allCandidatesCooling && !allFailedLogged) {
                log(TunnelEventCode.ALL_PROFILES_FAILED)
                allFailedLogged = true
            }
            engageLeakGuard()
            // Switching is truthful here: no protected endpoint is connected,
            // and the foreground notification explicitly says traffic is blocked.
            runtimePublisher.switching(profileName = null, protocol = null)
            updateNotification(
                getString(R.string.vpn_notification_blocked),
                getString(R.string.vpn_recovery_waiting),
            )
            delay(policy.nextRetryDelayMs(System.currentTimeMillis()))
        }
        return false
    }

    private suspend fun attemptReturnToPreferred(settings: FailoverSettings) {
        val policy = failoverPolicy ?: return
        val previous = policy.activeIndex
        val preferred = policy.preferredRecovery(System.currentTimeMillis()) ?: return
        engageLeakGuard()
        closeCoreIntentionally()
        policy.activate(preferred)
        if (runCatching { establishCandidate(preferred, switching = true, settings) }.isSuccess) return

        val preferredFailedAt = System.currentTimeMillis()
        policy.markFailed(preferred, preferredFailedAt)
        pendingConfig?.candidates?.getOrNull(preferred)?.let {
            healthStore.recordFailure(it.id, preferredFailedAt + settings.cooldownMs)
        }
        closeCoreIntentionally()
        policy.activate(previous)
        if (runCatching { establishCandidate(previous, switching = true, settings) }.isSuccess) return

        val previousFailedAt = System.currentTimeMillis()
        policy.markFailed(previous, previousFailedAt)
        pendingConfig?.candidates?.getOrNull(previous)?.let {
            healthStore.recordFailure(it.id, previousFailedAt + settings.cooldownMs)
        }
        recoverCandidates(
            settings = settings,
            continuous = true,
            failedIndexAlreadyMarked = true,
        )
    }

    private fun handleUnexpectedCoreStop(generation: Long) {
        if (generation != coreGeneration || explicitStop || recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            try {
                operationMutex.withLock {
                    if (generation == coreGeneration && !explicitStop) {
                        log(TunnelEventCode.CORE_PROCESS_STOPPED)
                        if (hasVerifiedConnection) {
                            val settings = readFailoverSettings()
                            if (settings.enabled) {
                                recoverCandidates(
                                    settings = settings,
                                    continuous = true,
                                    failedIndexAlreadyMarked = false,
                                )
                                if (!explicitStop && core != null) startMonitoring(settings)
                            } else {
                                fail(getString(R.string.vpn_all_profiles_failed), "core_stopped_failover_disabled")
                            }
                        } else {
                            fail(getString(R.string.vpn_native_start_failed), "core_stopped_during_start")
                        }
                    }
                }
            } finally {
                recoveryJob = null
            }
        }
    }

    private fun stopTunnel() {
        authorization.revoke()
        watchdogJob?.cancel()
        watchdogJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        statsJob?.cancel()
        statsJob = null
        notificationStatsJob?.cancel()
        notificationStatsJob = null
        closeCoreIntentionally()
        releaseLeakGuard()
        pendingConfig = null
        failoverPolicy = null
        hasVerifiedConnection = false
        log(TunnelEventCode.DISCONNECTED)
        runtimePublisher.disconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeCoreIntentionally() {
        coreGeneration++
        core?.close()
        core = null
    }

    private fun fail(userMessage: String, technicalCode: String) {
        watchdogJob?.cancel()
        watchdogJob = null
        statsJob?.cancel()
        statsJob = null
        notificationStatsJob?.cancel()
        notificationStatsJob = null
        val guarded = engageLeakGuard()
        authorization.revoke()
        hasVerifiedConnection = false
        closeCoreIntentionally()
        if (technicalCode.startsWith("failover_budget_exhausted")) log(TunnelEventCode.ALL_PROFILES_FAILED)
        if (guarded) log(TunnelEventCode.KILL_SWITCH_BLOCKING)
        runtimePublisher.failed(userMessage, technicalCode)
        updateNotification(
            if (guarded) getString(R.string.vpn_notification_blocked) else getString(R.string.vpn_notification_failed),
            userMessage,
        )
        if (!guarded) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !explicitStop) {
                delay(1_000)
                val state = TunnelRuntime.snapshot.value.state
                val active = state is ConnectionState.Connecting ||
                    state is ConnectionState.Switching || state is ConnectionState.Connected
                if (active && !authorization.isAuthorized()) {
                    val stopped = operationMutex.withLock {
                        val current = TunnelRuntime.snapshot.value.state
                        val stillActive = current is ConnectionState.Connecting ||
                            current is ConnectionState.Switching || current is ConnectionState.Connected
                        if (stillActive && !authorization.isAuthorized()) {
                            explicitStop = true
                            stopTunnel()
                            true
                        } else {
                            false
                        }
                    }
                    if (stopped) break
                }
                runtimePublisher.publish()
            }
        }
    }

    private fun classifyFailure(error: Throwable): String = CoreFailureClassifier.classify(error)

    private fun failureMessage(code: String): String = when (code) {
        "tunnel_verification_failed" -> getString(R.string.vpn_tunnel_verification_failed)
        "vpn_permission_revoked" -> getString(R.string.vpn_permission_revoked)
        "tun_establish_failed" -> getString(R.string.vpn_tun_establish_failed)
        "socket_protection_failed" -> getString(R.string.vpn_socket_protection_failed)
        "native_config_rejected" -> getString(R.string.vpn_native_config_rejected)
        "libbox_version_mismatch" -> getString(R.string.vpn_core_version_failed)
        "core_setup_failed" -> getString(R.string.vpn_core_setup_failed)
        "core_command_failed" -> getString(R.string.vpn_core_command_failed)
        "core_network_monitor_failed" -> getString(R.string.vpn_core_network_monitor_failed)
        "core_service_start_failed" -> getString(R.string.vpn_core_service_start_failed)
        "core_post_start_failed" -> getString(R.string.vpn_core_post_start_failed)
        else -> getString(R.string.vpn_native_start_failed)
    }

    private fun engageLeakGuard(): Boolean {
        val enabled = getSharedPreferences(PRODUCT_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH_ENABLED, true)
        if (!enabled || explicitStop || VpnService.prepare(this) != null) return false
        return runCatching {
            val descriptor = Builder()
                .setSession(getString(R.string.vpn_kill_switch_session))
                .setMtu(1_280)
                .addAddress("172.30.255.1", 30)
                .addAddress("fdfe:dcba:9877::1", 126)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .establish()
                ?: error("kill_switch_tun_failed")
            leakGuardDescriptor?.close()
            leakGuardDescriptor = descriptor
            true
        }.getOrDefault(false)
    }

    private fun releaseLeakGuard() {
        leakGuardDescriptor?.close()
        leakGuardDescriptor = null
    }

    private fun readFailoverSettings(): FailoverSettings {
        val preferences = getSharedPreferences(PRODUCT_PREFERENCES, Context.MODE_PRIVATE)
        val enabled = preferences.getBoolean(KEY_FAILOVER_ENABLED, true)
        return FailoverSettings(
            enabled = enabled,
            cooldownMs = preferences.getLong(KEY_FAILOVER_COOLDOWN_MS, 60_000)
                .coerceIn(5_000, 30 * 60_000),
            returnToPreferred = preferences.getBoolean(KEY_RETURN_TO_PREFERRED, false),
            qualitySwitchEnabled = enabled && preferences.getBoolean(KEY_QUALITY_SWITCH_ENABLED, true),
            weakLatencyThresholdMs = preferences.getLong(KEY_WEAK_LATENCY_THRESHOLD_MS, 1_500)
                .coerceIn(300, 10_000),
        )
    }

    private fun formatBytes(value: Long?): String {
        val bytes = value?.coerceAtLeast(0) ?: return "—"
        if (bytes < 1_024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = bytes.toDouble()
        var unit = -1
        do {
            amount /= 1_024.0
            unit++
        } while (amount >= 1_024 && unit < units.lastIndex)
        return String.format(java.util.Locale.getDefault(), "%.1f %s", amount, units[unit])
    }

    private fun formatDuration(totalSeconds: Long): String = String.format(
        java.util.Locale.getDefault(),
        "%02d:%02d:%02d",
        totalSeconds / 3_600,
        (totalSeconds % 3_600) / 60,
        totalSeconds % 60,
    )

    private fun logFailure(code: String) {
        val event = when (code) {
            "tunnel_verification_failed" -> TunnelEventCode.TUNNEL_VERIFICATION_FAILED
            "core_setup_failed" -> TunnelEventCode.CORE_SETUP_FAILED
            "libbox_version_mismatch" -> TunnelEventCode.CORE_VERSION_FAILED
            "native_config_rejected" -> TunnelEventCode.CORE_CONFIG_REJECTED
            "core_command_failed" -> TunnelEventCode.CORE_COMMAND_FAILED
            "core_network_monitor_failed" -> TunnelEventCode.CORE_NETWORK_MONITOR_FAILED
            "core_service_start_failed" -> TunnelEventCode.CORE_SERVICE_START_FAILED
            "core_post_start_failed" -> TunnelEventCode.CORE_POST_START_FAILED
            "tun_establish_failed", "vpn_permission_revoked" -> TunnelEventCode.TUN_ESTABLISH_FAILED
            "socket_protection_failed" -> TunnelEventCode.SOCKET_PROTECTION_FAILED
            else -> TunnelEventCode.CORE_START_FAILED
        }
        log(event)
    }

    private fun log(code: TunnelEventCode) {
        runCatching { eventLog.append(code) }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String, profileName: String? = null) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(status, profileName))
    }

    private fun buildNotification(status: String, profileName: String? = null): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 10, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stopIntent = Intent(this, FoxVpnService::class.java).setAction(ACTION_DISCONNECT)
        val stopPendingIntent = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(profileName ?: "FOXConnect")
            .setContentText(status)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.vpn_notification_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_CONNECT = "com.foxconnect.action.CONNECT"
        const val ACTION_DISCONNECT = "com.foxconnect.action.DISCONNECT"
        const val PRODUCT_PREFERENCES = "product_preferences"
        const val KEY_FAILOVER_ENABLED = "failover_enabled"
        const val KEY_FAILOVER_COOLDOWN_MS = "failover_cooldown_ms"
        const val KEY_RETURN_TO_PREFERRED = "return_to_preferred"
        const val KEY_KILL_SWITCH_ENABLED = "kill_switch_enabled"
        const val KEY_QUALITY_SWITCH_ENABLED = "quality_switch_enabled"
        const val KEY_WEAK_LATENCY_THRESHOLD_MS = "weak_latency_threshold_ms"
        private const val KEY_LANGUAGE = "language"
        private const val INITIAL_VERIFICATION_TIMEOUT_MS = 8_000
        private const val FAILOVER_VERIFICATION_TIMEOUT_MS = 6_000
        private const val DATA_PATH_EVIDENCE_ATTEMPTS = 25
        private const val DATA_PATH_EVIDENCE_POLL_MS = 100L
        private const val QUALITY_PERSIST_INTERVAL_MS = 30_000L
        private const val CHANNEL_ID = "vpn_tunnel"
        private const val NOTIFICATION_ID = 1001
    }
}
