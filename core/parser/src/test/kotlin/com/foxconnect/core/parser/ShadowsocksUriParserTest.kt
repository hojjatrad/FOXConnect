package com.foxconnect.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShadowsocksUriParserTest {
    @Test
    fun parsesSip002Base64UserInfo() {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:p@ss word".toByteArray())
        val result = ShadowsocksUriParser.parse("ss://$credentials@example.com:8388#Primary")

        assertTrue(result is ParseResult.Success)
        val profile = (result as ParseResult.Success).value
        assertEquals("aes-256-gcm", profile.method)
        assertEquals("p@ss word", profile.password)
        assertEquals("example.com", profile.host)
        assertEquals(8388, profile.port)
    }

    @Test
    fun parsesLegacyWholeAuthorityEncoding() {
        val authority = Base64.getEncoder().withoutPadding()
            .encodeToString("chacha20-ietf-poly1305:secret@vpn.example:443".toByteArray())
        val result = ShadowsocksUriParser.parse("ss://$authority#Legacy")

        assertTrue(result is ParseResult.Success)
        assertEquals("Legacy", (result as ParseResult.Success).value.name)
    }

    @Test
    fun rejectsLegacyCipherAndPluginInsteadOfIgnoringThem() {
        val weak = ShadowsocksUriParser.parse("ss://aes-256-cfb:secret@example.com:8388#Weak")
        val plugin = ShadowsocksUriParser.parse(
            "ss://aes-256-gcm:secret@example.com:8388?plugin=v2ray-plugin%3Btls#Plugin",
        )

        assertEquals("unsupported_method", (weak as ParseResult.Error).code)
        assertEquals("unsupported_plugin", (plugin as ParseResult.Error).code)
    }
}
