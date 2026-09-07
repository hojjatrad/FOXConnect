package com.foxconnect.core.engine

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {
    @Test
    fun `null restart intent is ignored rather than converted to connect`() {
        assertEquals(ServiceStartRequest.IGNORE, classifyServiceStart(null))
    }

    @Test
    fun `only explicit product actions can change tunnel state`() {
        assertEquals(ServiceStartRequest.CONNECT, classifyServiceStart(FoxVpnService.ACTION_CONNECT))
        assertEquals(ServiceStartRequest.DISCONNECT, classifyServiceStart(FoxVpnService.ACTION_DISCONNECT))
        assertEquals(ServiceStartRequest.IGNORE, classifyServiceStart("android.intent.action.MAIN"))
        assertEquals(ServiceStartRequest.IGNORE, classifyServiceStart("com.example.UNKNOWN"))
    }

    @Test
    fun `an established tunnel gets at most one automatic fallback per event`() {
        assertEquals(1, MAX_FAILOVER_ATTEMPTS_PER_EVENT)
    }

    @Test
    fun `package replacement is never treated as boot auto connect`() {
        assertFalse(DEFAULT_AUTO_CONNECT)
        assertTrue(isBootRestoreAction(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(isBootRestoreAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isBootRestoreAction(null))
    }
}
