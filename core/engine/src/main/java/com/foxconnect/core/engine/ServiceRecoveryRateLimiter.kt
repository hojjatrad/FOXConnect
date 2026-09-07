package com.foxconnect.core.engine

import android.annotation.SuppressLint
import android.content.Context

/** Persists a small restart budget so native/service crashes cannot form an unlimited sticky loop. */
internal class ServiceRecoveryRateLimiter(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    @SuppressLint("ApplySharedPref") // The crash-loop budget must be durable before recovery starts.
    fun acquire(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val storedWindow = preferences.getLong(KEY_WINDOW_STARTED_AT, 0)
        val validWindow = storedWindow in 1..nowEpochMs && nowEpochMs - storedWindow < WINDOW_MS
        val windowStartedAt = if (validWindow) storedWindow else nowEpochMs
        val count = if (validWindow) preferences.getInt(KEY_COUNT, 0).coerceAtLeast(0) else 0
        if (count >= MAX_RECOVERIES_PER_WINDOW) return false
        preferences.edit()
            .putLong(KEY_WINDOW_STARTED_AT, windowStartedAt)
            .putInt(KEY_COUNT, count + 1)
            .commit()
        return true
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Explicit user connect begins a new durable recovery window.
    fun reset() {
        preferences.edit().clear().commit()
    }

    companion object {
        internal const val MAX_RECOVERIES_PER_WINDOW = 3
        internal const val WINDOW_MS = 5 * 60_000L
        private const val PREFERENCES = "foxconnect.service.recovery"
        private const val KEY_WINDOW_STARTED_AT = "window_started_at"
        private const val KEY_COUNT = "count"
    }
}
