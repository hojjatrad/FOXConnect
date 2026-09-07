package com.foxconnect.core.engine

internal data class FailoverSettings(
    val enabled: Boolean = true,
    val watchdogIntervalMs: Long = 1_500,
    val probeTimeoutMs: Int = 1_500,
    val failureThreshold: Int = 2,
    val cooldownMs: Long = 60_000,
    val returnToPreferred: Boolean = false,
    val returnCheckIntervalMs: Long = 5 * 60_000,
) {
    init {
        require(watchdogIntervalMs in 500..5_000)
        require(probeTimeoutMs in 500..4_000)
        require(failureThreshold in 1..3)
        require(cooldownMs in 5_000..30 * 60_000)
        require(returnCheckIntervalMs in 30_000..24 * 60 * 60_000)
    }

    /** Worst-case failure detection target, excluding native reconnect time. */
    val maximumDetectionMs: Long
        get() = watchdogIntervalMs * failureThreshold + probeTimeoutMs.toLong() * failureThreshold
}

/** Deterministic cooldown/selection state; contains no network or Android dependencies. */
internal class FailoverPolicy(
    private val candidateCount: Int,
    activeIndex: Int,
    private val settings: FailoverSettings,
) {
    private val cooldownUntil = LongArray(candidateCount)
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

    fun activate(index: Int) {
        require(index in 0 until candidateCount)
        activeIndex = index
    }

    fun nextAvailable(nowMs: Long): Int? {
        if (!settings.enabled || candidateCount < 2) return null
        for (offset in 1 until candidateCount) {
            val index = (activeIndex + offset) % candidateCount
            if (cooldownUntil[index] <= nowMs) return index
        }
        return null
    }

    fun preferredRecovery(nowMs: Long): Int? {
        if (!settings.returnToPreferred || activeIndex == 0) return null
        return 0.takeIf { cooldownUntil[it] <= nowMs }
    }

    fun cooldownRemainingMs(index: Int, nowMs: Long): Long {
        require(index in 0 until candidateCount)
        return (cooldownUntil[index] - nowMs).coerceAtLeast(0)
    }
}
