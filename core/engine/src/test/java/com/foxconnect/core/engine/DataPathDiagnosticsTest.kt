package com.foxconnect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPathDiagnosticsTest {
    @Test
    fun `verification rejects every incomplete native data path`() {
        assertEquals(
            "tun_not_observed",
            DataPathReadinessVerifier.failureReason(snapshot(tun = false, physical = true, protected = 1)),
        )
        assertEquals(
            "physical_network_not_observed",
            DataPathReadinessVerifier.failureReason(snapshot(tun = true, physical = false, protected = 1)),
        )
        assertEquals(
            "socket_protection_not_observed",
            DataPathReadinessVerifier.failureReason(snapshot(tun = true, physical = true, protected = 0)),
        )
    }

    @Test
    fun `verification accepts TUN physical uplink and protected upstream socket`() {
        val diagnostics = DataPathDiagnostics()
        diagnostics.recordTunEstablished()
        diagnostics.recordPhysicalNetwork(true)
        diagnostics.recordProtectedSocket()
        diagnostics.recordBootstrapDnsRequest()
        diagnostics.recordBootstrapDnsSuccess()

        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.readyForVerifiedTraffic)
        assertEquals(1, snapshot.protectedSocketCount)
        assertEquals(1, snapshot.bootstrapDnsRequestCount)
        assertEquals(1, snapshot.bootstrapDnsSuccessCount)
        assertNull(DataPathReadinessVerifier.failureReason(snapshot))
    }

    @Test
    fun `native bidirectional traffic is mandatory evidence for Connected`() {
        assertEquals(false, DataPathReadinessVerifier.nativeTrafficObserved(null))
        assertEquals(
            false,
            DataPathReadinessVerifier.nativeTrafficObserved(NativeTrafficSnapshot(100, 0, 0, 0)),
        )
        assertTrue(
            DataPathReadinessVerifier.nativeTrafficObserved(NativeTrafficSnapshot(100, 80, 12, 9)),
        )
    }

    @Test
    fun `physical network loss makes an otherwise ready path unready`() {
        val diagnostics = DataPathDiagnostics()
        diagnostics.recordTunEstablished()
        diagnostics.recordPhysicalNetwork(true)
        diagnostics.recordProtectedSocket()
        diagnostics.recordPhysicalNetwork(false)

        assertEquals(
            "physical_network_not_observed",
            DataPathReadinessVerifier.failureReason(diagnostics.snapshot()),
        )
    }

    @Test
    fun `pre Android 13 route fallback never leaves an addressed family uncovered`() {
        assertTrue(TunRoutePolicy.requiresDefaultRoute(explicitRouteCount = 0, hasAddressFamily = true))
        assertEquals(false, TunRoutePolicy.requiresDefaultRoute(explicitRouteCount = 1, hasAddressFamily = true))
        assertEquals(false, TunRoutePolicy.requiresDefaultRoute(explicitRouteCount = 0, hasAddressFamily = false))
    }

    private fun snapshot(tun: Boolean, physical: Boolean, protected: Long) = DataPathSnapshot(
        tunEstablished = tun,
        physicalNetworkAvailable = physical,
        protectedSocketCount = protected,
        bootstrapDnsRequestCount = 0,
        bootstrapDnsSuccessCount = 0,
    )
}
