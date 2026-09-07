package com.foxconnect.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyUriParserTest {
    @Test
    fun parsesAuthenticatedSocks5() {
        val result = Socks5UriParser.parse("socks5://user:p%40ss@127.0.0.1:1080#Local")

        assertTrue(result is ParseResult.Success)
        val profile = (result as ParseResult.Success).value
        assertEquals("user", profile.username)
        assertEquals("p@ss", profile.password)
        assertEquals("Local", profile.name)
    }

    @Test
    fun parsesStrictHttpsConnectProxy() {
        val result = HttpProxyUriParser.parse(
            "https://user:secret@proxy.example:8443/connect?sni=edge.example&alpn=h2#Secure",
        )

        assertTrue(result is ParseResult.Success)
        val profile = (result as ParseResult.Success).value
        assertTrue(profile.tls.enabled)
        assertEquals("edge.example", profile.tls.serverName)
        assertEquals("/connect", profile.path)
    }

    @Test
    fun refusesTlsBypassAndAmbiguousCredentials() {
        val insecure = HttpProxyUriParser.parse(
            "https://proxy.example:443?allowInsecure=true#Unsafe",
        )
        val ambiguous = Socks5UriParser.parse("socks5://user@proxy.example:1080#Bad")

        assertEquals("insecure_tls_forbidden", (insecure as ParseResult.Error).code)
        assertFalse(ambiguous is ParseResult.Success)
    }
}
