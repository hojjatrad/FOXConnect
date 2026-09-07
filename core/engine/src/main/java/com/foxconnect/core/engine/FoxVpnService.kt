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

internal enum class ServiceStartRequest { CONNECT, DISCONNECT, IGNORE }
internal const val MAX_FAILOVER_ATTEMPTS_PER_EVENT = 1

internal fun classifyServiceStart(action: String?): ServiceStartRequest = when (action) {
    FoxVpnService.ACTION_CONNECT -> ServiceStartRequest.CONNECT
    FoxVpnService.ACTION_DISCONNECT -> ServiceStartRequest.DISCONNECT
    else -> ServiceStartRequest.IGNORE
}

internal object CoreFailureClassifier {
    fun classify(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val messages = chain.mapNotNull { it.message?.lowercase(java.util.Locale.ROOT) }.joinToString(" ")
        val stage = chain.filterIsInstance<CoreStartFailure>().firstOrNull()?.stage
        return when {
            "health_verification_failed" in messages -> "tunnel_verification_failed"
            "vpn_permission" in messages || "permission_revoked" in messages -> "vpn_permission_revoked"
            "tun_" in messages || "opentun" in messages || "builder.establish" in messages -> "tun_establish_failed"
            "protect_outbound_socket_failed" in messages -> "socket_protection_failed"
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
    private val runtimePublisher by lazy { TunnelStatePublisher(this) }
    private val authorization by lazy { TunnelRunAuthorization(this) }
    private var core: TypedLibboxCore? = null
    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private var notificationStatsJob: Job? = null
    private var watchdogJob: Job? = null
    private var heartbeatJob: Job? = null
    private var leakGuardDescriptor: ParcelFileDescriptor? = null
    private var pendingConfig: PendingEngineConfig? = null
    private var failoverPolicy: FailoverPolicy? = null
    private var coreGeneration = 0L
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
        // A null intent means Android recreated a killed sticky service. Never
        // turn that into a connection request: it caused the observed crash loop.
        val request = classifyServiceStart(intent?.action)
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
                    authorization.revoke()
                    connectionJob?.cancel()
                    watchdogJob?.cancel()
                    heartbeatJob?.cancel()
                    scope.launch { operationMutex.withLock { stopTunnel() } }
                }
                ServiceStartRequest.CONNECT -> {
                    if (!authorization.isAuthorized()) {
                        explicitStop = true
                        runtimePublisher.failed(getString(R.string.vpn_retry_blocked), "unauthorized_connect_blocked")
                        stopSelf(startId)
                        return Service.START_NOT_STICKY
                    }
                    explicitStop = false
                    startForegroundCompat(buildNotification(getString(R.string.vpn_notification_connecting)))
                    startHeartbeat()
                    if (connectionJob?.isActive != true && core == null) {
                        hasVerifiedConnection = false
                        connectionJob = scope.launch {
                            operationMutex.withLock { startTunnel() }
                        }
                    }
                }
                ServiceStartRequest.IGNORE -> Unit // Returned above.
            }
            // Native failure must stop here. Reconnection is only explicit (UI,
            // Quick Settings, or the configured boot receiver), never a sticky retry.
            Service.START_NOT_STICKY
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
        closeCoreIntentionally()
        releaseLeakGuard()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startTunnel() {
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
        failoverPolicy = policy

        // An explicit Connect is exactly one attempt against the selected
        // profile. Initial startup failure must never become a background walk
        // through every subscription profile.
        val target = config.activeIndex
        policy.activate(target)
        val connected = runCatching {
            establishCandidate(target, switching = false, settings)
        }.isSuccess
        if (connected) {
            startMonitoring(settings)
            return
        }
        closeCoreIntentionally()
        val failedAt = System.currentTimeMillis()
        policy.markFailed(target, failedAt)
        healthStore.recordFailure(config.candidates[target].id, failedAt + settings.cooldownMs)
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
            val verification = TunnelHealthVerifier().awaitVerified(
                maxAttempts = if (switching) 1 else 2,
                timeoutMs = if (switching) settings.probeTimeoutMs else 3_000,
            )
            if (!verification.successful) error("health_verification_failed")
            failoverPolicy?.activate(index)
            pendingConfig = config.withActiveIndex(index)
            store.write(requireNotNull(pendingConfig))
            healthStore.recordSuccess(candidate.id, verification.latencyMs)
            releaseLeakGuard()
            log(TunnelEventCode.VERIFIED)
            hasVerifiedConnection = true
            runtimePublisher.verified(
                profileName = candidate.profileName,
                protocol = candidate.protocol,
                switched = switching,
                latencyMs = verification.latencyMs,
            )
            updateNotification(getString(R.string.vpn_notification_connected), candidate.profileName)
            statsJob?.cancel()
            statsJob = scope.launch { TunnelStatsMonitor().run() }
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
            var lastReturnCheck = System.currentTimeMillis()
            while (isActive && !explicitStop) {
                delay(settings.watchdogIntervalMs)
                val result = TunnelHealthVerifier().probeTunnel(settings.probeTimeoutMs)
                runtimePublisher.healthChecked(result)
                if (result.successful) {
                    consecutiveFailures = 0
                    val now = System.currentTimeMillis()
                    if (now - lastReturnCheck >= settings.returnCheckIntervalMs) {
                        lastReturnCheck = now
                        operationMutex.withLock { attemptReturnToPreferred(settings) }
                    }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= settings.failureThreshold) {
                        log(TunnelEventCode.HEALTH_FAILED)
                        consecutiveFailures = 0
                        operationMutex.withLock {
                            if (!explicitStop) performFailover(settings)
                        }
                    }
                }
            }
        }
    }

    private suspend fun performFailover(settings: FailoverSettings): Boolean {
        val policy = failoverPolicy ?: return false
        val config = pendingConfig ?: return false
        val failedIndex = policy.activeIndex
        val failedAt = System.currentTimeMillis()
        policy.markFailed(failedIndex, failedAt)
        healthStore.recordFailure(config.candidates[failedIndex].id, failedAt + settings.cooldownMs)
        engageLeakGuard()
        closeCoreIntentionally()
        statsJob?.cancel()
        statsJob = null
        notificationStatsJob?.cancel()
        notificationStatsJob = null

        var target = policy.nextAvailable(System.currentTimeMillis())
        var attempts = 0
        while (target != null && !explicitStop && attempts < MAX_FAILOVER_ATTEMPTS_PER_EVENT) {
            attempts++
            policy.activate(target)
            val connected = runCatching { establishCandidate(target, switching = true, settings) }.isSuccess
            if (connected) return true
            closeCoreIntentionally()
            val targetFailedAt = System.currentTimeMillis()
            policy.markFailed(target, targetFailedAt)
            healthStore.recordFailure(config.candidates[target].id, targetFailedAt + settings.cooldownMs)
            target = policy.nextAvailable(targetFailedAt)
        }
        if (!explicitStop && config.candidates.isNotEmpty()) {
            fail(failureMessage(lastFailureCode), "failover_budget_exhausted_$lastFailureCode")
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
        performFailover(settings)
    }

    private fun handleUnexpectedCoreStop(generation: Long) {
        if (generation != coreGeneration || explicitStop) return
        scope.launch {
            operationMutex.withLock {
                if (generation == coreGeneration && !explicitStop) {
                    if (hasVerifiedConnection) {
                        performFailover(readFailoverSettings())
                    } else {
                        fail(getString(R.string.vpn_native_start_failed), "core_stopped_during_start")
                    }
                }
            }
        }
    }

    private fun stopTunnel() {
        authorization.revoke()
        watchdogJob?.cancel()
        watchdogJob = null
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
        return FailoverSettings(
            enabled = preferences.getBoolean(KEY_FAILOVER_ENABLED, true),
            cooldownMs = preferences.getLong(KEY_FAILOVER_COOLDOWN_MS, 60_000).coerceIn(5_000, 30 * 60_000),
            returnToPreferred = preferences.getBoolean(KEY_RETURN_TO_PREFERRED, false),
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
        private const val KEY_LANGUAGE = "language"
        private const val CHANNEL_ID = "vpn_tunnel"
        private const val NOTIFICATION_ID = 1001
    }
}
