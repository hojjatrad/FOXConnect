package com.foxconnect.core.engine

import com.foxconnect.core.model.AnyTlsProfile
import com.foxconnect.core.model.ConnectableProfile
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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Mandatory integration gate: CI provisions the exact checksum-pinned desktop CLI. */
class SingBoxNativeSchemaTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pinned native checker accepts every generated protocol config`() {
        val checker = checkNotNull(System.getenv("SING_BOX_CHECK")?.takeIf(String::isNotBlank)) {
            "SING_BOX_CHECK is mandatory; run scripts/install-sing-box-check.sh and export its path"
        }

        val version = run(checker, "version")
        if (!version.contains("sing-box version 1.14.0") || !version.contains(PINNED_REVISION)) {
            fail("SING_BOX_CHECK does not match the pinned core")
        }
        profiles().forEach { profile ->
            val config = temporaryFolder.newFile("${profile.protocol.scheme}-${profile.id}.json")
            config.writeText(SingBoxConfigFactory.fromProfile(profile))
            val result = ProcessBuilder(checker, "check", "-c", config.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader().use { it.readText() }
            val code = result.waitFor()
            if (code != 0) fail("Native schema check failed for ${profile.protocol}: $output")
        }
    }

    private fun run(executable: String, argument: String): String {
        val process = ProcessBuilder(executable, argument).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) fail("Unable to inspect native checker")
        return output
    }

    private fun profiles(): List<ConnectableProfile> {
        val strictTls = TlsOptions(enabled = true, serverName = "edge.example")
        val strictReality = TlsOptions(
            enabled = true,
            realityEnabled = true,
            serverName = "edge.example",
            fingerprint = "chrome",
            realityPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            realityShortId = "0123456789abcdef",
        )
        return listOf(
            VlessProfile("vless", "VLESS", "vless.example", 443, UUID, tls = strictTls),
            VlessProfile(
                "vless-reality",
                "VLESS Reality",
                "reality.example",
                443,
                UUID,
                flow = "xtls-rprx-vision",
                tls = strictReality,
            ),
            VlessProfile(
                "vless-http",
                "VLESS HTTP",
                "http-transport.example",
                443,
                UUID,
                tls = strictTls,
                transport = TransportOptions(TransportType.HTTP, path = "/edge", host = "cdn.example"),
            ),
            VlessProfile(
                "vless-ws",
                "VLESS WebSocket",
                "ws.example",
                443,
                UUID,
                tls = strictTls,
                transport = TransportOptions(TransportType.WEBSOCKET, path = "/socket", host = "cdn.example"),
            ),
            VlessProfile(
                "vless-quic",
                "VLESS QUIC",
                "quic.example",
                443,
                UUID,
                tls = strictTls,
                transport = TransportOptions(TransportType.QUIC),
            ),
            VlessProfile(
                "vless-grpc",
                "VLESS gRPC",
                "grpc.example",
                443,
                UUID,
                tls = strictTls,
                transport = TransportOptions(TransportType.GRPC, serviceName = "TunnelService"),
            ),
            VlessProfile(
                "vless-httpupgrade",
                "VLESS HTTPUpgrade",
                "upgrade.example",
                443,
                UUID,
                tls = strictTls,
                transport = TransportOptions(TransportType.HTTP_UPGRADE, path = "/up", host = "cdn.example"),
            ),

            VmessProfile("vmess", "VMess", "vmess.example", 443, UUID, tls = strictTls),
            TrojanProfile("trojan", "Trojan", "trojan.example", 443, "secret", strictTls),
            ShadowsocksProfile("ss", "SS", "ss.example", 8388, "aes-256-gcm", "secret"),
            Hysteria2Profile("hy2", "HY2", "hy2.example", 443, "secret", 50, 100, "salamander", "mask", strictTls),
            HysteriaProfile("hy1", "HY1", "hy1.example", 443, "token", "mask", 20, 80, strictTls),
            TuicProfile("tuic", "TUIC", "tuic.example", 443, UUID, "secret", "bbr", "native", strictTls),
            AnyTlsProfile("anytls", "AnyTLS", "anytls.example", 443, "secret", strictTls),
            WireGuardProfile(
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
                    ),
                ),
                dnsServers = listOf("1.1.1.1"),
            ),
            Socks5Profile("socks", "SOCKS", "socks.example", 1080, "user", "pass"),
            HttpProxyProfile("http", "HTTP", "http.example", 443, "user", "pass", "/connect", strictTls),
        )
    }

    private companion object {
        const val UUID = "bf000d23-0752-40b4-affe-68f7707a9661"
        const val PINNED_REVISION = "0b8995879f29a9b98ee027bc17b75e101445b238"
    }
}
