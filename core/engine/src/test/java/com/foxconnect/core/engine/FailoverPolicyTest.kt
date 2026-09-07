package com.foxconnect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {
    @Test
    fun `rotates unknown candidates and skips cooling failures`() {
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
    fun `lowest fresh verified tunnel latency wins regardless of list position`() {
        val policy = FailoverPolicy(4, 0, FailoverSettings())
        policy.seedQuality(1, latencyMs = 480, measuredAtMs = 9_000, nowMs = 10_000)
        policy.seedQuality(2, latencyMs = 95, measuredAtMs = 9_000, nowMs = 10_000)
        policy.seedQuality(3, latencyMs = 220, measuredAtMs = 9_000, nowMs = 10_000)

        assertEquals(2, policy.nextAvailable(nowMs = 10_000))
    }

    @Test
    fun `stale quality sample is ignored`() {
        val settings = FailoverSettings(qualitySampleMaxAgeMs = 60_000)
        val policy = FailoverPolicy(3, 0, settings)
        policy.seedQuality(1, latencyMs = 20, measuredAtMs = 1_000, nowMs = 100_000)
        policy.seedQuality(2, latencyMs = 200, measuredAtMs = 99_000, nowMs = 100_000)

        assertEquals(2, policy.nextAvailable(nowMs = 100_000))
    }

    @Test
    fun `endpoint reachability ranks unknown tunnels without mixing quality metrics`() {
        val policy = FailoverPolicy(4, 0, FailoverSettings())
        policy.seedEndpointReachability(1, latencyMs = 180, measuredAtMs = 9_000, nowMs = 10_000)
        policy.seedEndpointReachability(2, latencyMs = 45, measuredAtMs = 9_000, nowMs = 10_000)
        policy.seedQuality(3, latencyMs = 240, measuredAtMs = 9_000, nowMs = 10_000)

        // A verified tunneled measurement has higher confidence than a TCP endpoint probe.
        assertEquals(3, policy.nextAvailable(nowMs = 10_000))

        policy.markFailed(3, nowMs = 10_000)
        assertEquals(2, policy.nextAvailable(nowMs = 10_000))
    }

    @Test
    fun `meaningful measured improvement selects alternative but small gains do not`() {
        val settings = FailoverSettings(
            weakLatencyThresholdMs = 1_000,
            qualityMinimumImprovementMs = 250,
            qualityMinimumImprovementPercent = 35,
        )
        val policy = FailoverPolicy(3, 0, settings)
        policy.seedQuality(0, 1_600, 9_000, 10_000)
        policy.seedQuality(1, 700, 9_000, 10_000)
        policy.seedQuality(2, 1_450, 9_000, 10_000)

        assertEquals(1, policy.betterAlternative(nowMs = 10_000))

        val smallGain = FailoverPolicy(2, 0, settings)
        smallGain.seedQuality(0, 900, 9_000, 10_000)
        smallGain.seedQuality(1, 760, 9_000, 10_000)
        assertNull(smallGain.betterAlternative(nowMs = 10_000))
    }

    @Test
    fun `absolutely weak tunnel may trial a reachable endpoint without equating metrics`() {
        val policy = FailoverPolicy(
            3,
            0,
            FailoverSettings(weakLatencyThresholdMs = 1_000),
        )
        policy.seedQuality(0, 1_700, 9_000, 10_000)
        policy.seedEndpointReachability(1, 90, 9_000, 10_000)
        policy.seedEndpointReachability(2, 240, 9_000, 10_000)

        assertEquals(1, policy.betterAlternative(nowMs = 10_000))
    }

    @Test
    fun `quality hysteresis requires same candidate for consecutive samples`() {
        val gate = QualitySwitchHysteresis(requiredConsecutiveSamples = 3)

        assertNull(gate.observe(2))
        assertNull(gate.observe(1))
        assertNull(gate.observe(1))
        assertEquals(1, gate.observe(1))
        assertNull(gate.observe(null))
        assertNull(gate.observe(1))
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
    fun `bounded retry delay honors cooldown and configured cap`() {
        val settings = FailoverSettings(
            cooldownMs = 60_000,
            recoveryRetryMinMs = 5_000,
            recoveryRetryMaxMs = 30_000,
        )
        val policy = FailoverPolicy(1, 0, settings)
        policy.markFailed(0, nowMs = 10_000)

        assertEquals(30_000, policy.nextRetryDelayMs(nowMs = 10_000))
        assertEquals(5_000, policy.nextRetryDelayMs(nowMs = 69_000))
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
    fun `confirmed hard failure is detected within nine seconds`() {
        val settings = FailoverSettings()
        assertEquals(1, settings.failureThreshold)
        assertTrue(settings.maximumDetectionMs <= 9_000)
        assertTrue(settings.maximumDetectionMs < 10_000)
    }
}
