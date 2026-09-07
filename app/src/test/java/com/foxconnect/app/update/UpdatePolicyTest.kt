package com.foxconnect.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun `periodic update notices default on but preserve an explicit opt out`() {
        assertTrue(UpdatePolicy.periodicChecks(null))
        assertTrue(UpdatePolicy.periodicChecks(true))
        assertFalse(UpdatePolicy.periodicChecks(false))
    }

    @Test
    fun `one time migration enables notices for existing installs then preserves opt out`() {
        assertTrue(UpdatePolicy.migratedPeriodicChecks(policyRevision = 0, storedValue = false))
        assertFalse(UpdatePolicy.migratedPeriodicChecks(policyRevision = 1, storedValue = false))
        assertTrue(UpdatePolicy.migratedPrereleases(0, false, "debug"))
        assertTrue(UpdatePolicy.migratedPrereleases(0, true, "release"))
        assertFalse(UpdatePolicy.migratedPrereleases(1, false, "debug"))
    }

    @Test
    fun `diagnostic builds see prereleases by default while production remains stable`() {
        assertTrue(UpdatePolicy.includePrereleases(null, "debug"))
        assertFalse(UpdatePolicy.includePrereleases(null, "release"))
        assertFalse(UpdatePolicy.includePrereleases(false, "debug"))
        assertTrue(UpdatePolicy.includePrereleases(true, "release"))
    }

    @Test
    fun `only a strictly newer unannounced version produces a notice`() {
        assertTrue(UpdatePolicy.shouldNotify(lastNotifiedVersion = 15, availableVersion = 16))
        assertFalse(UpdatePolicy.shouldNotify(lastNotifiedVersion = 16, availableVersion = 16))
        assertFalse(UpdatePolicy.shouldNotify(lastNotifiedVersion = 17, availableVersion = 16))
    }

    @Test
    fun `automatic check timing stays prompt and bounded`() {
        assertTrue(UpdatePolicy.INITIAL_CHECK_DELAY_MINUTES in 15..60)
        assertTrue(UpdatePolicy.CHECK_INTERVAL_HOURS in 12..24)
        assertTrue(UpdatePolicy.FLEX_INTERVAL_HOURS in 1L until UpdatePolicy.CHECK_INTERVAL_HOURS)
        assertTrue(UpdatePolicy.RETRY_BACKOFF_MINUTES >= 10)
    }
}
