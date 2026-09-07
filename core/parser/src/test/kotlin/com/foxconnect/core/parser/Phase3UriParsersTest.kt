package com.foxconnect.core.parser

import com.foxconnect.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class Phase3UriParsersTest {
    @Test
    fun parsesModernVmessV2Json() {
        val payload = """{
          "v":"2","ps":"VM edge","add":"vm.example","port":"443",
          "id":"bf000d23-0752-40b4-affe-68f7707a9661","aid":"0","scy":"auto",
          "net":"ws","host":"cdn.example","path":"/socket","tls":"tls",
          "sni":"edge.example","alpn":"h2,http/1.1","fp":"chrome"
        }""".trimIndent()
        val link = "vmess://" + Base64.getEncoder().withoutPadding()
            .encodeToString(payload.toByteArray())

        val result = VmessUriParser.parse(link)

        assertTrue(result is ParseResult.Success)
        val profile = (result as ParseResult.Success).value
        assertEquals("VM edge", profile.name)
        assertEquals("auto", profile.security)
        assertTrue(profile.tls.enabled)
        assertEquals("edge.example", profile.tls.serverName)
        assertEquals(TransportType.WEBSOCKET, profile.transport.type)
        assertEquals("/socket", profile.transport.path)
    }

    @Test
    fun refusesLegacyOrTlsBypassingVmess() {
        fun link(aid: String, insecure: Boolean): String {
            val json = """{"v":"2","add":"vm.example","port":"443","id":"bf000d23-0752-40b4-affe-68f7707a9661","aid":"$aid","scy":"auto","net":"tcp","tls":"tls","allowInsecure":$insecure}"""
            return "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        }

        assertEquals("legacy_vmess_forbidden", (VmessUriParser.parse(link("1", false)) as ParseResult.Error).code)
        assertEquals("insecure_tls_forbidden", (VmessUriParser.parse(link("0", true)) as ParseResult.Error).code)
    }

    @Test
    fun parsesHysteriaFamiliesWithStrictTls() {
        val hy2 = Hysteria2UriParser.parse(
            "hy2://p%40ss@hy.example:443?sni=edge.example&obfs=salamander&obfs-password=mask&upmbps=50&downmbps=100#HY2",
        )
        val hy1 = HysteriaUriParser.parse(
            "hysteria://hy.example:443?auth=token&peer=edge.example&upmbps=20&downmbps=80&protocol=udp#HY1",
        )

        assertTrue(hy2 is ParseResult.Success)
        assertEquals("p@ss", (hy2 as ParseResult.Success).value.password)
        assertEquals("salamander", hy2.value.obfsType)
        assertTrue(hy1 is ParseResult.Success)
        assertEquals(20, (hy1 as ParseResult.Success).value.upMbps)
        assertEquals("token", hy1.value.auth)
    }

    @Test
    fun refusesMissingHysteriaBandwidthAndInsecureHysteria2() {
        val missing = HysteriaUriParser.parse("hysteria://hy.example:443?auth=token&upmbps=20")
        val insecure = Hysteria2UriParser.parse("hysteria2://token@hy.example:443?insecure=1")

        assertEquals("invalid_bandwidth", (missing as ParseResult.Error).code)
        assertEquals("insecure_tls_forbidden", (insecure as ParseResult.Error).code)
    }

    @Test
    fun parsesTuicV5AndAnyTls() {
        val tuic = TuicUriParser.parse(
            "tuic://bf000d23-0752-40b4-affe-68f7707a9661:secret@tuic.example:443?congestion_control=bbr&udp_relay_mode=native&sni=edge.example#TUIC",
        )
        val anyTls = AnyTlsUriParser.parse("anytls://p%2Bass@any.example:443?sni=edge.example#Any")

        assertTrue(tuic is ParseResult.Success)
        assertEquals("bbr", (tuic as ParseResult.Success).value.congestionControl)
        assertEquals("secret", tuic.value.password)
        assertTrue(anyTls is ParseResult.Success)
        assertEquals("p+ass", (anyTls as ParseResult.Success).value.password)
        assertFalse(anyTls.value.tls.serverName.isNullOrBlank())
    }

    @Test
    fun rejectsUnknownQuicOptionInsteadOfIgnoringIt() {
        val result = Hysteria2UriParser.parse("hy2://token@hy.example:443?unsupported=value")
        assertEquals("unsupported_option", (result as ParseResult.Error).code)
    }
}
