package com.foxconnect.core.engine

import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.model.ProtocolType
import com.foxconnect.core.model.TunnelSnapshot
import com.foxconnect.core.model.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TunnelRuntime {
    private val mutableSnapshot = MutableStateFlow(TunnelSnapshot())
    val snapshot: StateFlow<TunnelSnapshot> = mutableSnapshot.asStateFlow()

    internal fun connecting(profileName: String, protocol: ProtocolType) {
        mutableSnapshot.value = TunnelSnapshot(
            state = ConnectionState.Connecting(profileName),
            protocol = protocol,
            profileName = profileName,
        )
    }

    internal fun switching(profileName: String?, protocol: ProtocolType?) {
        mutableSnapshot.update {
            it.copy(
                state = ConnectionState.Switching(profileName),
                protocol = protocol,
                profileName = profileName,
                stats = TunnelStats.Empty,
            )
        }
    }

    internal fun verified(
        profileName: String,
        protocol: ProtocolType,
        switched: Boolean = false,
        latencyMs: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        mutableSnapshot.update {
            it.copy(
                state = ConnectionState.Connected(profileName, now),
                protocol = protocol,
                profileName = profileName,
                stats = TunnelStats(connectedAtEpochMs = now, latencyMs = latencyMs),
                failoverCount = it.failoverCount + if (switched) 1 else 0,
                lastHealthCheckEpochMs = now,
            )
        }
    }

    internal fun updateStats(transform: (TunnelStats) -> TunnelStats) {
        mutableSnapshot.update { snapshot ->
            if (snapshot.state is ConnectionState.Connected) {
                snapshot.copy(stats = transform(snapshot.stats))
            } else {
                snapshot
            }
        }
    }

    internal fun healthChecked(result: HealthProbeResult) {
        mutableSnapshot.update { snapshot ->
            if (snapshot.state is ConnectionState.Connected) {
                snapshot.copy(
                    stats = snapshot.stats.copy(
                        latencyMs = result.latencyMs ?: snapshot.stats.latencyMs,
                    ),
                    lastHealthCheckEpochMs = System.currentTimeMillis(),
                )
            } else {
                snapshot
            }
        }
    }

    internal fun failed(userMessage: String, technicalMessage: String? = null) {
        mutableSnapshot.update {
            it.copy(state = ConnectionState.Failed(userMessage, technicalMessage), stats = TunnelStats.Empty)
        }
    }

    internal fun disconnecting() {
        mutableSnapshot.update {
            it.copy(state = ConnectionState.Disconnecting, stats = TunnelStats.Empty)
        }
    }

    internal fun disconnected() {
        mutableSnapshot.value = TunnelSnapshot(state = ConnectionState.Disconnected)
    }

    internal fun replace(snapshot: TunnelSnapshot) {
        mutableSnapshot.value = snapshot
    }
}
