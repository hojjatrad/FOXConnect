package com.foxconnect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TunnelEventCodecTest {
    @Test
    fun `all connection lifecycle and repair events survive persistence`() {
        val entries = TunnelEventCode.entries.mapIndexed { index, code ->
            TunnelEvent(timestampEpochMs = 1_000L + index, code = code)
        }

        val payload = TunnelEventCodec.encode(entries)
        assertFalse(payload.toString(Charsets.US_ASCII).contains("example.com"))
        assertEquals(entries, TunnelEventCodec.decode(payload, 200))
    }

    @Test
    fun `corrupt and unknown rows cannot hide valid event rows`() {
        val payload = "bad\n100|UNKNOWN\n200|VERIFIED\n-3|HEALTH_FAILED".toByteArray()

        assertEquals(
            listOf(
                TunnelEvent(200, TunnelEventCode.VERIFIED),
                TunnelEvent(0, TunnelEventCode.HEALTH_FAILED),
            ),
            TunnelEventCodec.decode(payload, 200),
        )
    }
}
