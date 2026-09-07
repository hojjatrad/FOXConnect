package com.foxconnect.core.parser

import com.foxconnect.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessUriParserTest {
    @Test
    fun `parses reality websocket fields`() {
        val uri = "vless://11111111-1111-4111-8111-111111111111@example.com:443" +
            "?security=reality&type=ws&sni=cdn.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=12ab" +
            "&path=%2Fedge&host=edge.example.com#My%20Server"

        val result = VlessUriParser.parse(uri)
        assertTrue(result is ParseResult.Success)
        val profile = (result as ParseResult.Success).value
        assertEquals("My Server", profile.name)
        assertEquals(TransportType.WEBSOCKET, profile.transport.type)
        assertEquals("/edge", profile.transport.path)
        assertEquals("edge.example.com", profile.transport.host)
        assertTrue(profile.tls.realityEnabled)
    }

    @Test
    fun `accepts HTTP and unencrypted QUIC transports implemented by core`() {
        val base = "vless://11111111-1111-4111-8111-111111111111@example.com:443"
        val http = VlessUriParser.parse("$base?type=http&path=%2Fedge&host=cdn.example") as ParseResult.Success
        val quic = VlessUriParser.parse("$base?type=quic&quicSecurity=none") as ParseResult.Success

        assertEquals(TransportType.HTTP, http.value.transport.type)
        assertEquals(TransportType.QUIC, quic.value.transport.type)
    }

    @Test
    fun `rejects QUIC encryption that pinned core cannot represent`() {
        val result = VlessUriParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@example.com:443?type=quic&quicSecurity=aes-128-gcm",
        )
        assertTrue(result is ParseResult.Error)
        assertEquals("unsupported_quic_encryption", (result as ParseResult.Error).code)
    }

    @Test
    fun `returns readable error for missing reality key`() {
        val result = VlessUriParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=reality",
        )
        assertTrue(result is ParseResult.Error)
        assertEquals("missing_reality_key", (result as ParseResult.Error).code)
    }

    @Test
    fun `rejects invalid port without throwing`() {
        val result = VlessUriParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@example.com",
        )
        assertTrue(result is ParseResult.Error)
        assertEquals("invalid_port", (result as ParseResult.Error).code)
    }
}
