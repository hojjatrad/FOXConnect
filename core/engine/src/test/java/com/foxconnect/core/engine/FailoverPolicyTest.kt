package com.foxconnect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {
    @Test
    fun `rotates candidates and skips cooling failures`() {
        val settings = FailoverSettings(cooldownMs = 60_000)
        val policy = FailoverPolicy(candidateCount = 3, activeIndex = 0, settings = settings)

        policy.markFailed(0, nowMs = 1_000)
        assertEquals(1, policy.nextAvailable(nowMs = 1_000))
        policy.activate(1)
        policy.markFailed(1, nowMs = 2_000)
        assertEquals(2, policy.nextAvailable(nowMs = 2_000))
        policy.activate(2)
        policy.markFailed(2, nowMs = 3_000)
        assertNull(policy.nextAvailable(nowMs = 3_000))
    }

    @Test
    fun `cooldown expires and makes candidate eligible again`() {
        val policy = FailoverPolicy(2, 1, FailoverSettings(cooldownMs = 5_000))
        policy.markFailed(0, nowMs = 10_000)

        assertNull(policy.nextAvailable(nowMs = 14_999))
        assertEquals(0, policy.nextAvailable(nowMs = 15_000))
        assertEquals(1, policy.cooldownRemainingMs(0, nowMs = 14_999))
    }

    @Test
    fun `return to preferred remains explicitly opt in`() {
        val disabled = FailoverPolicy(2, 1, FailoverSettings(returnToPreferred = false))
        val enabled = FailoverPolicy(2, 1, FailoverSettings(returnToPreferred = true))

        assertNull(disabled.preferredRecovery(nowMs = 1_000))
        assertEquals(0, enabled.preferredRecovery(nowMs = 1_000))
        enabled.markFailed(0, nowMs = 1_000)
        assertNull(enabled.preferredRecovery(nowMs = 2_000))
    }

    @Test
    fun `default detector budget leaves reconnect time under ten second target`() {
        val settings = FailoverSettings()
        assertTrue(settings.maximumDetectionMs <= 6_000)
        assertTrue(settings.maximumDetectionMs < 10_000)
    }
}
