package com.foxconnect.core.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val profileName: String) : ConnectionState
    data class Switching(val nextProfileName: String?) : ConnectionState
    data class Connected(val profileName: String, val verifiedAtEpochMs: Long) : ConnectionState
    data class Failed(val userMessage: String, val technicalMessage: String? = null) : ConnectionState
}

data class TunnelStats(
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val rxBytesPerSecond: Long? = null,
    val txBytesPerSecond: Long? = null,
    val connectedAtEpochMs: Long? = null,
    val exitIp: String? = null,
    val countryCode: String? = null,
    val latencyMs: Long? = null,
) {
    companion object { val Empty = TunnelStats() }
}

data class TunnelSnapshot(
    val state: ConnectionState = ConnectionState.Disconnected,
    val protocol: ProtocolType? = null,
    val profileName: String? = null,
    val stats: TunnelStats = TunnelStats.Empty,
    val failoverCount: Int = 0,
    val lastHealthCheckEpochMs: Long? = null,
)
