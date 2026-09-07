package com.foxconnect.core.engine

internal data class FailoverSettings(
    val enabled: Boolean = true,
    val watchdogIntervalMs: Long = 3_000,
    val probeTimeoutMs: Int = 3_000,
    // One failed round already means the primary plus every independent
    // confirmation origin failed; a second full round only delays recovery.
    val failureThreshold: Int = 1,
    val cooldownMs: Long = 60_000,
    val returnToPreferred: Boolean = false,
    val returnCheckIntervalMs: Long = 5 * 60_000,
    val qualitySwitchEnabled: Boolean = true,
    val weakLatencyThresholdMs: Long = 1_500,
    val qualityFailureThreshold: Int = 4,
    val qualityMinimumImprovementMs: Long = 250,
    val qualityMinimumImprovementPercent: Int = 35,
    val qualityMinimumConnectedMs: Long = 30_000,
    val qualitySwitchCooldownMs: Long = 3 * 60_000,
    val qualitySampleMaxAgeMs: Long = 24 * 60 * 60_000,
    val recoveryRetryMinMs: Long = 5_000,
    val recoveryRetryMaxMs: Long = 60_000,
) {
    init {
        require(watchdogIntervalMs in 500..5_000)
        require(probeTimeoutMs in 500..4_000)
        require(failureThreshold in 1..3)
        require(cooldownMs in 5_000..30 * 60_000)
        require(returnCheckIntervalMs in 30_000..24 * 60 * 60_000)
        require(weakLatencyThresholdMs in 300..10_000)
        require(qualityFailureThreshold in 2..10)
        require(qualityMinimumImprovementMs in 50..5_000)
        require(qualityMinimumImprovementPercent in 10..200)
        require(qualityMinimumConnectedMs in 10_000..10 * 60_000)
        require(qualitySwitchCooldownMs in 30_000..30 * 60_000)
        require(qualitySampleMaxAgeMs in 60_000..7 * 24 * 60 * 60_000)
        require(recoveryRetryMinMs in 1_000..60_000)
        require(recoveryRetryMaxMs in recoveryRetryMinMs..5 * 60_000)
    }

    /** Worst-case hard-failure detection target, excluding native reconnect time. */
    val maximumDetectionMs: Long
        get() = (watchdogIntervalMs + (probeTimeoutMs.toLong() * 2L)) * failureThreshold
}

internal class QualitySwitchHysteresis(private val requiredConsecutiveSamples: Int) {
    private var candidate: Int? = null
    private var samples = 0

    init {
        require(requiredConsecutiveSamples >= 2)
    }

    fun observe(betterCandidate: Int?): Int? {
        if (betterCandidate == null) {
            candidate = null
            samples = 0
            return null
        }
        if (candidate == betterCandidate) {
            samples++
        } else {
            candidate = betterCandidate
            samples = 1
        }
        if (samples < requiredConsecutiveSamples) return null
        candidate = null
        samples = 0
        return betterCandidate
    }
}

/** Deterministic cooldown and quality ranking; contains no network or Android dependencies. */
internal class FailoverPolicy(
    val candidateCount: Int,
    activeIndex: Int,
    private val settings: FailoverSettings,
) {
    private val cooldownUntil = LongArray(candidateCount)
    private val qualityLatencyMs = LongArray(candidateCount) { UNKNOWN_LATENCY }
    private val qualityMeasuredAtMs = LongArray(candidateCount)
    private val endpointLatencyMs = LongArray(candidateCount) { UNKNOWN_LATENCY }
    private val endpointMeasuredAtMs = LongArray(candidateCount)
    var activeIndex: Int = activeIndex
        private set

    init {
        require(candidateCount > 0)
        require(activeIndex in 0 until candidateCount)
    }

    fun markFailed(index: Int, nowMs: Long) {
        require(index in 0 until candidateCount)
        cooldownUntil[index] = maxOf(cooldownUntil[index], nowMs + settings.cooldownMs)
    }

    fun seedCooldown(index: Int, cooldownUntilMs: Long?, nowMs: Long) {
        require(index in 0 until candidateCount)
        val persisted = cooldownUntilMs?.takeIf { it > nowMs } ?: return
        cooldownUntil[index] = maxOf(cooldownUntil[index], persisted)
    }

    fun seedQuality(index: Int, latencyMs: Long?, measuredAtMs: Long?, nowMs: Long) {
        require(index in 0 until candidateCount)
        val latency = latencyMs?.takeIf { it in 0..MAX_LATENCY_MS } ?: return
        val measuredAt = measuredAtMs?.takeIf { it in 0..nowMs } ?: return
        if (nowMs - measuredAt > settings.qualitySampleMaxAgeMs) return
        qualityLatencyMs[index] = latency
        qualityMeasuredAtMs[index] = measuredAt
    }

    fun seedEndpointReachability(index: Int, latencyMs: Long?, measuredAtMs: Long?, nowMs: Long) {
        require(index in 0 until candidateCount)
        val latency = latencyMs?.takeIf { it in 0..MAX_LATENCY_MS } ?: return
        val measuredAt = measuredAtMs?.takeIf { it in 0..nowMs } ?: return
        if (nowMs - measuredAt > settings.qualitySampleMaxAgeMs) return
        endpointLatencyMs[index] = latency
        endpointMeasuredAtMs[index] = measuredAt
    }

    fun recordHealthy(index: Int, latencyMs: Long?, nowMs: Long) {
        require(index in 0 until candidateCount)
        cooldownUntil[index] = 0
        val latency = latencyMs?.coerceIn(0, MAX_LATENCY_MS) ?: return
        val previous = qualityLatencyMs[index]
        qualityLatencyMs[index] = if (previous == UNKNOWN_LATENCY) {
            latency
        } else {
            ((previous * 3L) + latency) / 4L
        }
        qualityMeasuredAtMs[index] = nowMs.coerceAtLeast(0)
    }

    fun activate(index: Int) {
        require(index in 0 until candidateCount)
        activeIndex = index
    }

    fun nextAvailable(nowMs: Long): Int? =
        bestAvailable(nowMs = nowMs, excluded = emptySet(), includeActive = false)

    fun bestAvailable(
        nowMs: Long,
        excluded: Set<Int>,
        includeActive: Boolean,
    ): Int? {
        if (!settings.enabled) return null
        return (0 until candidateCount)
            .asSequence()
            .filter { it !in excluded }
            .filter { includeActive || it != activeIndex }
            .filter { cooldownUntil[it] <= nowMs }
            .minWithOrNull(
                compareBy<Int> { qualityRank(it, nowMs) }
                    .thenBy { rankingLatencyValue(it, nowMs) }
                    .thenBy { circularDistance(it) },
            )
    }

    fun betterAlternative(nowMs: Long): Int? {
        if (!settings.enabled || !settings.qualitySwitchEnabled || candidateCount < 2) return null
        val current = qualityValue(activeIndex, nowMs)
        if (current == UNKNOWN_LATENCY) return null
        val alternative = (0 until candidateCount)
            .asSequence()
            .filter { it != activeIndex && cooldownUntil[it] <= nowMs }
            .filter { qualityValue(it, nowMs) != UNKNOWN_LATENCY }
            .minByOrNull { qualityValue(it, nowMs) }
        if (alternative != null) {
            val better = qualityValue(alternative, nowMs)
            val improvement = current - better
            val percentageGate = current * 100L >=
                better * (100L + settings.qualityMinimumImprovementPercent)
            val weakEnough = current >= settings.weakLatencyThresholdMs ||
                improvement >= settings.qualityMinimumImprovementMs * 2
            if (improvement >= settings.qualityMinimumImprovementMs && percentageGate && weakEnough) {
                return alternative
            }
        }
        // Endpoint TCP latency is not treated as tunnel quality. It is only a
        // reachability tie-breaker when the current verified tunnel is already
        // above the absolute weak threshold and no verified better tunnel exists.
        if (current < settings.weakLatencyThresholdMs) return null
        return bestAvailable(nowMs, excluded = emptySet(), includeActive = false)
            ?.takeIf { qualityRank(it, nowMs) < 2 }
            ?.takeIf { qualityValue(it, nowMs) == UNKNOWN_LATENCY }
    }

    fun preferredRecovery(nowMs: Long): Int? {
        if (!settings.enabled || !settings.returnToPreferred || activeIndex == 0) return null
        return 0.takeIf { cooldownUntil[it] <= nowMs }
    }

    fun cooldownRemainingMs(index: Int, nowMs: Long): Long {
        require(index in 0 until candidateCount)
        return (cooldownUntil[index] - nowMs).coerceAtLeast(0)
    }

    fun nextRetryDelayMs(nowMs: Long): Long {
        val earliest = cooldownUntil.minOrNull()?.takeIf { it > nowMs }
        return if (earliest == null) {
            settings.recoveryRetryMinMs
        } else {
            (earliest - nowMs).coerceIn(settings.recoveryRetryMinMs, settings.recoveryRetryMaxMs)
        }
    }

    private fun qualityRank(index: Int, nowMs: Long): Int = when {
        qualityValue(index, nowMs) != UNKNOWN_LATENCY -> 0
        endpointValue(index, nowMs) != UNKNOWN_LATENCY -> 1
        else -> 2
    }

    private fun qualityValue(index: Int, nowMs: Long): Long {
        val measuredAt = qualityMeasuredAtMs[index]
        if (measuredAt <= 0 || measuredAt > nowMs || nowMs - measuredAt > settings.qualitySampleMaxAgeMs) {
            return UNKNOWN_LATENCY
        }
        return qualityLatencyMs[index]
    }

    private fun endpointValue(index: Int, nowMs: Long): Long {
        val measuredAt = endpointMeasuredAtMs[index]
        if (measuredAt <= 0 || measuredAt > nowMs || nowMs - measuredAt > settings.qualitySampleMaxAgeMs) {
            return UNKNOWN_LATENCY
        }
        return endpointLatencyMs[index]
    }

    private fun rankingLatencyValue(index: Int, nowMs: Long): Long =
        qualityValue(index, nowMs).takeIf { it != UNKNOWN_LATENCY }
            ?: endpointValue(index, nowMs)

    private fun circularDistance(index: Int): Int =
        (index - activeIndex + candidateCount) % candidateCount

    private companion object {
        const val UNKNOWN_LATENCY = Long.MAX_VALUE
        const val MAX_LATENCY_MS = 60_000L
    }
}
