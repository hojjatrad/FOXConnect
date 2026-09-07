package com.foxconnect.app.ui

import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.model.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresentationPolicyTest {
    @Test
    fun `connected failover candidate overrides stale repository selection`() {
        val displayed = HomePresentationPolicy.displayedConnection(
            state = ConnectionState.Connected("Verified B", 100),
            runtimeName = "Verified B",
            runtimeProtocol = ProtocolType.TROJAN,
            selectedName = "Originally selected A",
            selectedProtocol = ProtocolType.VLESS,
        )

        assertEquals("Verified B", displayed.name)
        assertEquals(ProtocolType.TROJAN, displayed.protocol)
        assertTrue(displayed.isRuntimeActive)
    }

    @Test
    fun `switching immediately displays candidate under verification`() {
        val displayed = HomePresentationPolicy.displayedConnection(
            state = ConnectionState.Switching("Candidate C"),
            runtimeName = "Candidate C",
            runtimeProtocol = ProtocolType.SHADOWSOCKS,
            selectedName = "Selected A",
            selectedProtocol = ProtocolType.VLESS,
        )

        assertEquals("Candidate C", displayed.name)
        assertEquals(ProtocolType.SHADOWSOCKS, displayed.protocol)
        assertTrue(displayed.isRuntimeActive)
    }

    @Test
    fun `disconnected state displays repository selection`() {
        val displayed = HomePresentationPolicy.displayedConnection(
            state = ConnectionState.Disconnected,
            runtimeName = "Old runtime",
            runtimeProtocol = ProtocolType.TROJAN,
            selectedName = "Selected A",
            selectedProtocol = ProtocolType.VLESS,
        )

        assertEquals("Selected A", displayed.name)
        assertEquals(ProtocolType.VLESS, displayed.protocol)
        assertFalse(displayed.isRuntimeActive)
    }
}
