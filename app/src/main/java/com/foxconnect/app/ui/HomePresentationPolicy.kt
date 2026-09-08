package com.foxconnect.app.ui

import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.model.ProtocolType

internal data class DisplayedConnection(
    val name: String?,
    val protocol: ProtocolType?,
    val isRuntimeActive: Boolean,
)

/** The service snapshot is authoritative while a tunnel candidate is active. */
internal object HomePresentationPolicy {
    fun displayedConnection(
        state: ConnectionState,
        runtimeName: String?,
        runtimeProtocol: ProtocolType?,
        selectedName: String?,
        selectedProtocol: ProtocolType?,
    ): DisplayedConnection {
        val runtimeIsAuthoritative = state is ConnectionState.Connecting ||
            state is ConnectionState.Switching ||
            state is ConnectionState.Connected ||
            state is ConnectionState.Disconnecting
        if (runtimeIsAuthoritative && !runtimeName.isNullOrBlank()) {
            return DisplayedConnection(runtimeName, runtimeProtocol, isRuntimeActive = true)
        }
        return DisplayedConnection(selectedName, selectedProtocol, isRuntimeActive = false)
    }
}
