package com.foxconnect.core.parser

import com.foxconnect.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrojanUriParserTest {
    @Test
    fun parsesStrictTlsWebSocketAndStableIdentity() {
        val first = TrojanUriParser.parse(
            "trojan://p%40ssword@example.com:443?security=tls&sni=edge.example.com&type=ws&path=%2Fsocket&host=cdn.example.com#Primary",
        )
        val renamed = TrojanUriParser.parse(
            "trojan://p%40ssword@example.com:443?host=cdn.example.com&path=%2Fsocket&type=ws&sni=edge.example.com&security=tls#Renamed",
        )

        assertTrue(first is ParseResult.Success)
        assertTrue(renamed is ParseResult.Success)
        val profile = (first as ParseResult.Success).value
        assertEquals("p@ssword", profile.password)
        assertEquals("edge.example.com", profile.tls.serverName)
        assertEquals(TransportType.WEBSOCKET, profile.transport.type)
        assertEquals((renamed as ParseResult.Success).value.id, profile.id)
    }

    @Test
    fun credentialChangeChangesIdentity() {
        val first = (TrojanUriParser.parse("trojan://one@example.com:443#One") as ParseResult.Success).value
        val second = (TrojanUriParser.parse("trojan://two@example.com:443#Two") as ParseResult.Success).value

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun refusesInsecureTlsRequest() {
        val result = TrojanUriParser.parse("trojan://secret@example.com:443?allowInsecure=1#Unsafe")

        assertTrue(result is ParseResult.Error)
        assertEquals("insecure_tls_forbidden", (result as ParseResult.Error).code)
    }
}
