package com.foxconnect.app.update

internal object UpdatePolicy {
    const val CURRENT_POLICY_REVISION = 1
    const val CHECK_INTERVAL_HOURS = 24L
    const val FLEX_INTERVAL_HOURS = 6L
    const val INITIAL_CHECK_DELAY_MINUTES = 15L
    const val RETRY_BACKOFF_MINUTES = 15L

    fun periodicChecks(storedValue: Boolean?): Boolean = storedValue ?: true

    fun includePrereleases(storedValue: Boolean?, assetChannel: String): Boolean =
        storedValue ?: assetChannel.equals("debug", ignoreCase = true)

    fun migratedPeriodicChecks(policyRevision: Int, storedValue: Boolean?): Boolean =
        if (policyRevision < CURRENT_POLICY_REVISION) true else periodicChecks(storedValue)

    fun migratedPrereleases(
        policyRevision: Int,
        storedValue: Boolean?,
        assetChannel: String,
    ): Boolean = if (
        policyRevision < CURRENT_POLICY_REVISION &&
        assetChannel.equals("debug", ignoreCase = true)
    ) {
        true
    } else {
        includePrereleases(storedValue, assetChannel)
    }

    fun shouldNotify(lastNotifiedVersion: Int, availableVersion: Int): Boolean =
        availableVersion > lastNotifiedVersion
}
