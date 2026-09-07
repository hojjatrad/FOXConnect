package com.foxconnect.core.engine

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {
    @Test
    fun `unauthorized null restart intent remains ignored`() {
        assertEquals(ServiceStartRequest.IGNORE, classifyServiceStart(null))
    }

    @Test
    fun `persistently authorized null restart becomes bounded recovery`() {
        assertEquals(
            ServiceStartRequest.RECOVER,
            classifyServiceStart(action = null, authorizedSystemRecovery = true),
        )
    }

    @Test
    fun `only product actions or authorized recovery can change tunnel state`() {
        assertEquals(ServiceStartRequest.CONNECT, classifyServiceStart(FoxVpnService.ACTION_CONNECT))
        assertEquals(ServiceStartRequest.DISCONNECT, classifyServiceStart(FoxVpnService.ACTION_DISCONNECT))
        assertEquals(ServiceStartRequest.IGNORE, classifyServiceStart("android.intent.action.MAIN", true))
        assertEquals(ServiceStartRequest.IGNORE, classifyServiceStart("com.example.UNKNOWN", true))
    }

    @Test
    fun `each recovery round is bounded but can inspect several ranked candidates`() {
        assertEquals(4, MAX_FAILOVER_ATTEMPTS_PER_ROUND)
        assertTrue(MAX_FAILOVER_ATTEMPTS_PER_ROUND > 1)
    }

    @Test
    fun `sticky process recovery has a small persistent crash loop budget`() {
        assertEquals(3, ServiceRecoveryRateLimiter.MAX_RECOVERIES_PER_WINDOW)
        assertEquals(5 * 60_000L, ServiceRecoveryRateLimiter.WINDOW_MS)
    }

    @Test
    fun `package replacement is never treated as boot auto connect`() {
        assertFalse(DEFAULT_AUTO_CONNECT)
        assertTrue(isBootRestoreAction(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(isBootRestoreAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isBootRestoreAction(null))
    }
}
