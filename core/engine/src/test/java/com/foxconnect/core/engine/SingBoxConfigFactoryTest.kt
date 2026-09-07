package com.foxconnect.core.engine

import com.foxconnect.core.model.AnyTlsProfile
import com.foxconnect.core.model.HttpProxyProfile
import com.foxconnect.core.model.Hysteria2Profile
import com.foxconnect.core.model.HysteriaProfile
import com.foxconnect.core.model.ShadowsocksProfile
import com.foxconnect.core.model.Socks5Profile
import com.foxconnect.core.model.TlsOptions
import com.foxconnect.core.model.TransportOptions
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.TrojanProfile
import com.foxconnect.core.model.TuicProfile
import com.foxconnect.core.model.VlessProfile
import com.foxconnect.core.model.VmessProfile
import com.foxconnect.core.model.WireGuardPeer
import com.foxconnect.core.model.WireGuardProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigFactoryTest {
    @Test
    fun `creates vless reality websocket outbound and strict tun`() {
        val profile = VlessProfile(
            id = "id",
            name = "test",
            host = "example.com",
            port = 443,
            uuid = "11111111-1111-4111-8111-111111111111",
            tls = TlsOptions(
                enabled = true,
                realityEnabled = true,
                serverName = "cdn.example.com",
                fingerprint = "chrome",
                realityPublicKey = "public-key",
                realityShortId = "12ab",
            ),
            transport = TransportOptions(TransportType.WEBSOCKET, path = "/edge", host = "edge.example.com"),
        )
        val root = Json.parseToJsonElement(SingBoxConfigFactory.fromVless(profile)).jsonObject
        val inbound = root.getValue("inbounds").jsonArray.first().jsonObject
        assertTrue(inbound.getValue("strict_route").jsonPrimitive.content.toBoolean())
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals("vless", outbound.getValue("type").jsonPrimitive.content)
        assertEquals("bootstrap-dns", outbound.getValue("domain_resolver").jsonPrimitive.content)
        assertEquals("ws", outbound.getValue("transport").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(outbound.getValue("tls").jsonObject.getValue("reality").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertEquals(
            "bootstrap-dns",
            root.getValue("route").jsonObject.getValue("default_domain_resolver").jsonPrimitive.content,
        )
    }

    @Test
    fun `refuses transports absent from pinned libbox instead of emitting invalid JSON`() {
        listOf(TransportType.SPLIT_HTTP, TransportType.MKCP).forEach { transport ->
            val profile = VlessProfile(
                id = "unsupported-${transport.name}",
                name = "Unsupported",
                host = "192.0.2.1",
                port = 443,
                uuid = "11111111-1111-4111-8111-111111111111",
                transport = TransportOptions(transport, path = "/fixture"),
            )
            assertThrows(UnsupportedEngineProfileException::class.java) {
                SingBoxConfigFactory.fromProfile(profile)
            }
        }
    }

    @Test
    fun `emits every V2Ray transport implemented by pinned core`() {
        val transports = listOf(
            TransportOptions(TransportType.HTTP, path = "/edge", host = "cdn.example"),
            TransportOptions(TransportType.WEBSOCKET, path = "/socket", host = "cdn.example"),
            TransportOptions(TransportType.QUIC),
            TransportOptions(TransportType.GRPC, serviceName = "TunnelService"),
            TransportOptions(TransportType.HTTP_UPGRADE, path = "/up", host = "cdn.example"),
        )
        assertEquals(
            listOf("http", "ws", "quic", "grpc", "httpupgrade"),
            transports.mapIndexed { index, transport ->
                val profile = VlessProfile(
                    id = "transport-$index",
                    name = "Transport",
                    host = "192.0.2.1",
                    port = 443,
                    uuid = "11111111-1111-4111-8111-111111111111",
                    transport = transport,
                )
                outbound(profile).getValue("transport").jsonObject.getValue("type").jsonPrimitive.content
            },
        )
    }

    @Test
    fun `creates strict trojan TLS outbound`() {
        val profile = TrojanProfile(
            id = "id",
            name = "trojan",
            host = "example.com",
            port = 443,
            password = "secret",
            tls = TlsOptions(enabled = true, serverName = "edge.example.com"),
            transport = TransportOptions(TransportType.GRPC, serviceName = "tunnel"),
        )

        val root = Json.parseToJsonElement(SingBoxConfigFactory.fromProfile(profile)).jsonObject
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals("trojan", outbound.getValue("type").jsonPrimitive.content)
        assertTrue(outbound.getValue("tls").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertEquals("grpc", outbound.getValue("transport").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `creates Shadowsocks outbound`() {
        val profile = ShadowsocksProfile(
            id = "id",
            name = "ss",
            host = "example.com",
            port = 8388,
            method = "aes-256-gcm",
            password = "secret",
        )

        val root = Json.parseToJsonElement(SingBoxConfigFactory.fromProfile(profile)).jsonObject
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals("shadowsocks", outbound.getValue("type").jsonPrimitive.content)
        assertEquals("aes-256-gcm", outbound.getValue("method").jsonPrimitive.content)
        assertEquals("secret", outbound.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun `creates modern VMess websocket outbound`() {
        val profile = VmessProfile(
            id = "vmess",
            name = "VMess",
            host = "vm.example",
            port = 443,
            uuid = "bf000d23-0752-40b4-affe-68f7707a9661",
            security = "auto",
            packetEncoding = "xudp",
            tls = TlsOptions(enabled = true, serverName = "edge.example"),
            transport = TransportOptions(TransportType.WEBSOCKET, path = "/socket", host = "cdn.example"),
        )

        val outbound = outbound(profile)
        assertEquals("vmess", outbound.getValue("type").jsonPrimitive.content)
        assertEquals("0", outbound.getValue("alter_id").jsonPrimitive.content)
        assertEquals("xudp", outbound.getValue("packet_encoding").jsonPrimitive.content)
        assertEquals("ws", outbound.getValue("transport").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `creates Hysteria TUIC and AnyTLS outbounds`() {
        val tls = TlsOptions(enabled = true, serverName = "edge.example")
        val profiles = listOf(
            Hysteria2Profile("hy2", "HY2", "hy.example", 443, "secret", 50, 100, "salamander", "mask", tls),
            HysteriaProfile("hy1", "HY1", "hy.example", 443, "token", "mask", 20, 80, tls),
            TuicProfile("tuic", "TUIC", "tuic.example", 443, "bf000d23-0752-40b4-affe-68f7707a9661", "secret", "bbr", "native", tls),
            AnyTlsProfile("any", "AnyTLS", "any.example", 443, "secret", tls),
        )

        assertEquals(listOf("hysteria2", "hysteria", "tuic", "anytls"), profiles.map { outbound(it).getValue("type").jsonPrimitive.content })
        assertEquals("salamander", outbound(profiles[0]).getValue("obfs").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("token", outbound(profiles[1]).getValue("auth_str").jsonPrimitive.content)
        assertEquals("bbr", outbound(profiles[2]).getValue("congestion_control").jsonPrimitive.content)
        assertTrue(outbound(profiles[3]).getValue("tls").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `creates WireGuard endpoint and profile DNS`() {
        val profile = WireGuardProfile(
            id = "wg",
            name = "WireGuard",
            host = "wg.example",
            port = 51820,
            privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            localAddresses = listOf("10.8.0.2/32"),
            peers = listOf(
                WireGuardPeer(
                    address = "wg.example",
                    port = 51820,
                    publicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                    allowedIps = listOf("0.0.0.0/0", "::/0"),
                    persistentKeepaliveSeconds = 25,
                ),
            ),
            dnsServers = listOf("1.1.1.1"),
            mtu = 1380,
        )

        val root = Json.parseToJsonElement(SingBoxConfigFactory.fromProfile(profile)).jsonObject
        val endpoint = root.getValue("endpoints").jsonArray.single().jsonObject
        val dns = root.getValue("dns").jsonObject.getValue("servers").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tag").jsonPrimitive.content == "profile-dns-0" }
        assertEquals("wireguard", endpoint.getValue("type").jsonPrimitive.content)
        assertEquals("1380", endpoint.getValue("mtu").jsonPrimitive.content)
        assertEquals("wg.example", endpoint.getValue("peers").jsonArray.single().jsonObject.getValue("address").jsonPrimitive.content)
        assertEquals("udp", dns.getValue("type").jsonPrimitive.content)
        assertEquals("1.1.1.1", dns.getValue("server").jsonPrimitive.content)
    }

    @Test
    fun `creates SOCKS5 and HTTPS CONNECT outbounds`() {
        val socks = Socks5Profile("socks", "SOCKS", "127.0.0.1", 1080, "user", "pass")
        val http = HttpProxyProfile(
            id = "http",
            name = "HTTP",
            host = "proxy.example",
            port = 443,
            username = "user",
            password = "pass",
            path = "/connect",
            tls = TlsOptions(enabled = true, serverName = "proxy.example"),
        )

        val socksOutbound = Json.parseToJsonElement(SingBoxConfigFactory.fromProfile(socks))
            .jsonObject.getValue("outbounds").jsonArray.first().jsonObject
        val httpOutbound = Json.parseToJsonElement(SingBoxConfigFactory.fromProfile(http))
            .jsonObject.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals("socks", socksOutbound.getValue("type").jsonPrimitive.content)
        assertEquals("5", socksOutbound.getValue("version").jsonPrimitive.content)
        assertEquals("http", httpOutbound.getValue("type").jsonPrimitive.content)
        assertTrue(httpOutbound.getValue("tls").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
    }

    private fun outbound(profile: com.foxconnect.core.model.ConnectableProfile) =
        Json.parseToJsonElement(SingBoxConfigFactory.fromProfile(profile))
            .jsonObject.getValue("outbounds").jsonArray.first().jsonObject
}
