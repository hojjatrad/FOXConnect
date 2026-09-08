package com.foxconnect.core.engine

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.foxconnect.core.model.ConnectableProfile

class AndroidTunnelController(context: Context) {
    private val appContext = context.applicationContext
    private val configStore = EngineConfigStore(appContext)
    private val eventLog = TunnelEventLog(appContext)
    private val runtimePublisher = TunnelStatePublisher(appContext)
    private val authorization = TunnelRunAuthorization(appContext)

    fun permissionIntent(): Intent? = VpnService.prepare(appContext)

    /** Prepares and starts the service without allowing a bad generated config,
     * encrypted-store failure, or Android service restriction to crash the UI. */
    fun connect(profile: ConnectableProfile, failoverCandidates: List<ConnectableProfile> = emptyList()): Boolean =
        runCatching {
            val selected = profile.toPendingCandidate()
            val fallbacks = failoverCandidates.asSequence()
                .filterNot { it.id == profile.id }
                .distinctBy { it.id }
                .mapNotNull { candidate -> runCatching { candidate.toPendingCandidate() }.getOrNull() }
                .take(PendingEngineConfig.MAX_FAILOVER_CANDIDATES - 1)
                .toList()
            configStore.write(PendingEngineConfig(listOf(selected) + fallbacks))
            authorization.authorize()
            runCatching { eventLog.append(TunnelEventCode.CONNECT_REQUESTED) }
            runtimePublisher.connecting(profile.name, profile.protocol)
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, FoxVpnService::class.java).setAction(FoxVpnService.ACTION_CONNECT),
            )
            true
        }.getOrElse { error ->
            authorization.revoke()
            configStore.clear()
            if (error is UnsupportedEngineProfileException) {
                runtimePublisher.failed(
                    appContext.getString(R.string.vpn_transport_unsupported),
                    error.reasonCode,
                )
            } else {
                runtimePublisher.failed(appContext.getString(R.string.vpn_start_failed), "service_start_failed")
            }
            false
        }

    private fun ConnectableProfile.toPendingCandidate() = PendingEngineCandidate(
        id = id,
        profileName = name,
        protocol = protocol,
        json = SingBoxConfigFactory.fromProfile(this),
    )

    fun disconnect(): Boolean {
        runCatching { eventLog.append(TunnelEventCode.DISCONNECT_REQUESTED) }
        authorization.revoke()
        configStore.clear()
        runtimePublisher.disconnecting()
        return runCatching {
            appContext.startService(
                Intent(appContext, FoxVpnService::class.java).setAction(FoxVpnService.ACTION_DISCONNECT),
            )
            true
        }.getOrElse {
            runtimePublisher.failed(appContext.getString(R.string.vpn_stop_failed), "service_stop_failed")
            false
        }
    }

    fun clearPersistedConnection() {
        authorization.revoke()
        configStore.clear()
    }

    fun reportInputError(message: String, detail: String? = null) {
        runtimePublisher.failed(message, detail)
    }
}
