package com.foxconnect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthVerifierTest {
    @Test
    fun `strict TLS probe accepts any syntactically valid HTTP response`() {
        listOf(100, 200, 204, 301, 401, 429, 500, 599).forEach { status ->
            assertTrue("status $status", HealthProbeSemantics.isValidHttpResponse(status))
        }
        listOf(-1, 0, 99, 600, 999).forEach { status ->
            assertFalse("status $status", HealthProbeSemantics.isValidHttpResponse(status))
        }
    }

    @Test
    fun `watchdog confirmation excludes primary and covers every other provider`() {
        assertEquals(listOf(1, 2), HealthProbeSemantics.confirmationIndices(0, 3))
        assertEquals(listOf(0, 2), HealthProbeSemantics.confirmationIndices(1, 3))
        assertEquals(listOf(0, 1), HealthProbeSemantics.confirmationIndices(2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `confirmation rejects impossible primary index`() {
        HealthProbeSemantics.confirmationIndices(3, 3)
    }
}
