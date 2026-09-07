package com.foxconnect.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WireGuardConfigParserTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32))
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val preSharedKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    @Test
    fun parsesStandardWireGuardIniWithIpv6AndDns() {
        val config = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.8.0.2/32, fd00::2/128
            DNS = 1.1.1.1
            MTU = 1380

            [Peer]
            PublicKey = $publicKey
            PresharedKey = $preSharedKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = [2001:db8::1]:51820
            PersistentKeepalive = 25
            Reserved = 1, 2, 3
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)

        assertTrue(result is ParseResult.Success)
        val profile = (result as ParseResult.Success).value
        assertEquals("2001:db8::1", profile.host)
        assertEquals(51820, profile.port)
        assertEquals(listOf("10.8.0.2/32", "fd00::2/128"), profile.localAddresses)
        assertEquals(listOf("1.1.1.1"), profile.dnsServers)
        assertEquals(25, profile.peers.single().persistentKeepaliveSeconds)
        assertEquals(listOf(1, 2, 3), profile.peers.single().reserved)
    }

    @Test
    fun universalImporterAcceptsWireGuardFile() {
        val config = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.8.0.2/32
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = wg.example:51820
        """.trimIndent()

        val batch = UniversalConfigImporter.importText(config)

        assertEquals(1, batch.configs.size)
        assertEquals("wg.example", batch.configs.single().profile.host)
        assertTrue(batch.issues.isEmpty())
    }

    @Test
    fun rejectsUnknownAndAmbiguousOptions() {
        val unknown = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.8.0.2/32
            Table = off
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = wg.example:51820
        """.trimIndent()
        val duplicate = """
            [Interface]
            PrivateKey = $privateKey
            PrivateKey = $privateKey
            Address = 10.8.0.2/32
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = wg.example:51820
        """.trimIndent()

        assertEquals("unsupported_option", (WireGuardConfigParser.parse(unknown) as ParseResult.Error).code)
        assertEquals("duplicate_option", (WireGuardConfigParser.parse(duplicate) as ParseResult.Error).code)
    }
}
