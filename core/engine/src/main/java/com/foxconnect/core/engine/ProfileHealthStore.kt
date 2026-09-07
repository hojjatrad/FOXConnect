package com.foxconnect.core.engine

import android.content.Context

data class ProfileHealthMetric(
    val endpointLatencyMs: Long? = null,
    val endpointMeasuredAtEpochMs: Long? = null,
    val tunnelLatencyMs: Long? = null,
    val tunnelMeasuredAtEpochMs: Long? = null,
    val cooldownUntilEpochMs: Long? = null,
    val endpointProbeFailed: Boolean = false,
) {
    /** Compatibility aliases used by endpoint-latency UI. */
    val latencyMs: Long?
        get() = endpointLatencyMs
    val measuredAtEpochMs: Long?
        get() = endpointMeasuredAtEpochMs
}

/**
 * Stores non-secret health metadata separately from credential-bearing profile storage.
 * Endpoint TCP reachability and verified tunneled HTTPS quality use distinct keys and APIs.
 */
class ProfileHealthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun recordEndpointSuccess(
        profileId: String,
        latencyMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!validId(profileId)) return
        preferences.edit().apply {
            latencyMs?.coerceIn(0, MAX_LATENCY_MS)?.let { putLong(key(ENDPOINT_LATENCY, profileId), it) }
            putLong(key(ENDPOINT_MEASURED_AT, profileId), nowMs.coerceAtLeast(0))
            remove(key(COOLDOWN_UNTIL, profileId))
            remove(key(ENDPOINT_FAILED, profileId))
        }.apply()
    }

    fun recordTunnelSuccess(
        profileId: String,
        latencyMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!validId(profileId)) return
        preferences.edit().apply {
            latencyMs?.coerceIn(0, MAX_LATENCY_MS)?.let { putLong(key(TUNNEL_LATENCY, profileId), it) }
            putLong(key(TUNNEL_MEASURED_AT, profileId), nowMs.coerceAtLeast(0))
            remove(key(COOLDOWN_UNTIL, profileId))
        }.apply()
    }

    fun recordEndpointProbeFailure(profileId: String, nowMs: Long = System.currentTimeMillis()) {
        if (!validId(profileId)) return
        preferences.edit()
            .remove(key(ENDPOINT_LATENCY, profileId))
            .putLong(key(ENDPOINT_MEASURED_AT, profileId), nowMs.coerceAtLeast(0))
            .putBoolean(key(ENDPOINT_FAILED, profileId), true)
            .apply()
    }

    fun recordFailure(profileId: String, cooldownUntilMs: Long) {
        if (!validId(profileId)) return
        preferences.edit()
            .putLong(key(COOLDOWN_UNTIL, profileId), cooldownUntilMs.coerceAtLeast(0))
            .apply()
    }

    fun read(profileIds: Collection<String>): Map<String, ProfileHealthMetric> = profileIds
        .asSequence()
        .filter(::validId)
        .take(MAX_PROFILE_METRICS)
        .associateWith { id ->
            ProfileHealthMetric(
                endpointLatencyMs = preferences.longOrNull(key(ENDPOINT_LATENCY, id)),
                endpointMeasuredAtEpochMs = preferences.longOrNull(key(ENDPOINT_MEASURED_AT, id)),
                tunnelLatencyMs = preferences.longOrNull(key(TUNNEL_LATENCY, id)),
                tunnelMeasuredAtEpochMs = preferences.longOrNull(key(TUNNEL_MEASURED_AT, id)),
                cooldownUntilEpochMs = preferences.longOrNull(key(COOLDOWN_UNTIL, id)),
                endpointProbeFailed = preferences.getBoolean(key(ENDPOINT_FAILED, id), false),
            )
        }

    fun remove(profileId: String) {
        if (!validId(profileId)) return
        preferences.edit()
            .remove(key(ENDPOINT_LATENCY, profileId))
            .remove(key(ENDPOINT_MEASURED_AT, profileId))
            .remove(key(TUNNEL_LATENCY, profileId))
            .remove(key(TUNNEL_MEASURED_AT, profileId))
            .remove(key(COOLDOWN_UNTIL, profileId))
            .remove(key(ENDPOINT_FAILED, profileId))
            .apply()
    }

    private fun android.content.SharedPreferences.longOrNull(name: String): Long? =
        if (contains(name)) getLong(name, 0) else null

    private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 128 &&
        id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun key(prefix: String, id: String) = "$prefix$id"

    private companion object {
        const val FILE_NAME = "profile_health_v1"
        // Legacy alpha8 keys become the endpoint-specific metric without migration or loss.
        const val ENDPOINT_LATENCY = "latency_"
        const val ENDPOINT_MEASURED_AT = "measured_"
        const val TUNNEL_LATENCY = "tunnel_latency_"
        const val TUNNEL_MEASURED_AT = "tunnel_measured_"
        const val COOLDOWN_UNTIL = "cooldown_"
        const val ENDPOINT_FAILED = "endpoint_failed_"
        const val MAX_PROFILE_METRICS = 5_000
        const val MAX_LATENCY_MS = 60_000L
    }
}
