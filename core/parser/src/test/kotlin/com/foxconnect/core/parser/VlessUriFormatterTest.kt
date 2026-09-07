package com.foxconnect.core.parser

import com.foxconnect.core.model.TlsOptions
import com.foxconnect.core.model.TransportOptions
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessUriFormatterTest {
    @Test
    fun formattedProfileRoundTripsThroughStrictParser() {
        val profile = VlessProfile(
            id = "ignored",
            name = "آزمایش تهران",
            host = "2001:db8::1",
            port = 443,
            uuid = "11111111-1111-4111-8111-111111111111",
            flow = "xtls-rprx-vision",
            tls = TlsOptions(
                enabled = true,
                realityEnabled = true,
                serverName = "example.com",
                fingerprint = "chrome",
                realityPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                realityShortId = "a1b2",
                alpn = listOf("h2", "http/1.1"),
            ),
            transport = TransportOptions(type = TransportType.GRPC, serviceName = "edge service"),
        )

        val raw = VlessUriFormatter.format(profile)
        val parsed = VlessUriParser.parse(raw)

        assertTrue(parsed is ParseResult.Success)
        val value = (parsed as ParseResult.Success).value
        assertEquals(profile.name, value.name)
        assertEquals(profile.host, value.host)
        assertEquals(profile.transport, value.transport)
        assertEquals(profile.tls, value.tls)
    }
}
