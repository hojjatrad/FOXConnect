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
import com.foxconnect.core.model.WireGuardProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class UnsupportedEngineProfileException(val reasonCode: String) :
    IllegalArgumentException(reasonCode)

object SingBoxConfigFactory {
    private val formatter = Json { prettyPrint = true }

    fun fromProfile(profile: ConnectableProfile): String = when (profile) {
        is VlessProfile -> fromVless(profile)
        is VmessProfile -> fromVmess(profile)
        is TrojanProfile -> fromTrojan(profile)
        is ShadowsocksProfile -> fromShadowsocks(profile)
        is Hysteria2Profile -> fromHysteria2(profile)
        is HysteriaProfile -> fromHysteria(profile)
        is TuicProfile -> fromTuic(profile)
        is AnyTlsProfile -> fromAnyTls(profile)
        is WireGuardProfile -> fromWireGuard(profile)
        is Socks5Profile -> fromSocks5(profile)
        is HttpProxyProfile -> fromHttpProxy(profile)
    }

    fun fromVless(profile: VlessProfile): String {
        val outbound = buildJsonObject {
            put("type", "vless")
            putCommonServer(profile)
            put("uuid", profile.uuid)
            profile.flow?.let { put("flow", it) }
            profile.packetEncoding?.let { put("packet_encoding", it) }
            if (profile.tls.enabled) put("tls", tlsObject(profile.tls))
            transportObject(profile.transport)?.let { put("transport", it) }
        }
        return rootConfig(outbound)
    }

    private fun fromVmess(profile: VmessProfile): String {
        val outbound = buildJsonObject {
            put("type", "vmess")
            putCommonServer(profile)
            put("uuid", profile.uuid)
            put("security", profile.security)
            put("alter_id", 0)
            profile.packetEncoding?.let { put("packet_encoding", it) }
            if (profile.tls.enabled) put("tls", tlsObject(profile.tls))
            transportObject(profile.transport)?.let { put("transport", it) }
        }
        return rootConfig(outbound)
    }

    private fun fromTrojan(profile: TrojanProfile): String {
        val outbound = buildJsonObject {
            put("type", "trojan")
            putCommonServer(profile)
            put("password", profile.password)
            put("tls", tlsObject(profile.tls))
            transportObject(profile.transport)?.let { put("transport", it) }
        }
        return rootConfig(outbound)
    }

    private fun fromShadowsocks(profile: ShadowsocksProfile): String {
        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            putCommonServer(profile)
            put("method", profile.method)
            put("password", profile.password)
        }
        return rootConfig(outbound)
    }

    private fun fromHysteria2(profile: Hysteria2Profile): String {
        val outbound = buildJsonObject {
            put("type", "hysteria2")
            putCommonServer(profile)
            put("password", profile.password)
            profile.upMbps?.let { put("up_mbps", it) }
            profile.downMbps?.let { put("down_mbps", it) }
            profile.obfsType?.let { type ->
                put("obfs", buildJsonObject {
                    put("type", type)
                    put("password", requireNotNull(profile.obfsPassword))
                })
            }
            put("tls", tlsObject(profile.tls))
        }
        return rootConfig(outbound)
    }

    private fun fromHysteria(profile: HysteriaProfile): String {
        val outbound = buildJsonObject {
            put("type", "hysteria")
            putCommonServer(profile)
            put("up_mbps", profile.upMbps)
            put("down_mbps", profile.downMbps)
            profile.obfs?.let { put("obfs", it) }
            profile.auth?.let { put("auth_str", it) }
            put("tls", tlsObject(profile.tls))
        }
        return rootConfig(outbound)
    }

    private fun fromTuic(profile: TuicProfile): String {
        val outbound = buildJsonObject {
            put("type", "tuic")
            putCommonServer(profile)
            put("uuid", profile.uuid)
            profile.password?.let { put("password", it) }
            put("congestion_control", profile.congestionControl)
            put("udp_relay_mode", profile.udpRelayMode)
            put("tls", tlsObject(profile.tls))
        }
        return rootConfig(outbound)
    }

    private fun fromAnyTls(profile: AnyTlsProfile): String {
        val outbound = buildJsonObject {
            put("type", "anytls")
            putCommonServer(profile)
            put("password", profile.password)
            put("tls", tlsObject(profile.tls))
        }
        return rootConfig(outbound)
    }

    private fun fromWireGuard(profile: WireGuardProfile): String {
        val endpoint = buildJsonObject {
            put("type", "wireguard")
            put("tag", "proxy")
            put("domain_resolver", "bootstrap-dns")
            put("mtu", profile.mtu)
            put("address", buildJsonArray {
                profile.localAddresses.forEach { add(JsonPrimitive(it)) }
            })
            put("private_key", profile.privateKey)
            profile.listenPort?.let { put("listen_port", it) }
            put("peers", buildJsonArray {
                profile.peers.forEach { peer ->
                    add(buildJsonObject {
                        put("address", peer.address)
                        put("port", peer.port)
                        put("public_key", peer.publicKey)
                        peer.preSharedKey?.let { put("pre_shared_key", it) }
                        put("allowed_ips", buildJsonArray {
                            peer.allowedIps.forEach { add(JsonPrimitive(it)) }
                        })
                        peer.persistentKeepaliveSeconds?.let {
                            put("persistent_keepalive_interval", it)
                        }
                        if (peer.reserved.isNotEmpty()) {
                            put("reserved", buildJsonArray {
                                peer.reserved.forEach { add(JsonPrimitive(it)) }
                            })
                        }
                    })
                }
            })
        }
        return rootConfig(outbound = null, endpoint = endpoint, dnsServers = profile.dnsServers)
    }

    private fun fromSocks5(profile: Socks5Profile): String {
        val outbound = buildJsonObject {
            put("type", "socks")
            putCommonServer(profile)
            put("version", "5")
            profile.username?.let { put("username", it) }
            profile.password?.let { put("password", it) }
        }
        return rootConfig(outbound)
    }

    private fun fromHttpProxy(profile: HttpProxyProfile): String {
        val outbound = buildJsonObject {
            put("type", "http")
            putCommonServer(profile)
            profile.username?.let { put("username", it) }
            profile.password?.let { put("password", it) }
            profile.path?.let { put("path", it) }
            if (profile.tls.enabled) put("tls", tlsObject(profile.tls))
        }
        return rootConfig(outbound)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCommonServer(profile: ConnectableProfile) {
        put("tag", "proxy")
        put("server", profile.host)
        put("server_port", profile.port)
        // Resolve only the proxy endpoint on Android's explicitly physical
        // Network. Protected DNS then travels through the proxy, avoiding the
        // bootstrap cycle: proxy -> DoH -> unresolved proxy.
        put("domain_resolver", "bootstrap-dns")
    }

    private fun rootConfig(
        outbound: JsonObject?,
        endpoint: JsonObject? = null,
        dnsServers: List<String> = emptyList(),
    ): String {
        require((outbound == null) != (endpoint == null)) { "Exactly one proxy target is required" }
        val root = buildJsonObject {
            put("log", buildJsonObject {
                // Native text is never forwarded to Android logs. Keep the core
                // itself at error level so endpoint metadata is not retained.
                put("level", "error")
                put("timestamp", false)
            })
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    // Used only to bootstrap the proxy endpoint. The typed
                    // platform resolver binds it to a NOT_VPN Android Network.
                    add(buildJsonObject {
                        put("type", "local")
                        put("tag", "bootstrap-dns")
                    })
                    if (dnsServers.isEmpty()) {
                        add(buildJsonObject {
                            put("type", "https")
                            put("tag", "secure-dns")
                            put("server", "1.1.1.1")
                            put("server_port", 443)
                            put("path", "/dns-query")
                            put("detour", "proxy")
                            put("tls", buildJsonObject {
                                put("enabled", true)
                                put("server_name", "cloudflare-dns.com")
                            })
                        })
                    } else {
                        dnsServers.forEachIndexed { index, server ->
                            add(buildJsonObject {
                                put("type", "udp")
                                put("tag", "profile-dns-$index")
                                put("server", server)
                                put("server_port", 53)
                                put("detour", "proxy")
                            })
                        }
                    }
                })
                put("final", if (dnsServers.isEmpty()) "secure-dns" else "profile-dns-0")
                put("strategy", "prefer_ipv4")
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put(
                        "address",
                        JsonArray(
                            listOf(
                                JsonPrimitive("172.19.0.1/30"),
                                JsonPrimitive("fdfe:dcba:9876::1/126"),
                            ),
                        ),
                    )
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "mixed")
                })
            })
            put("outbounds", buildJsonArray {
                outbound?.let { add(it) }
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            })
            endpoint?.let { value ->
                put("endpoints", buildJsonArray { add(value) })
            }
            put("route", buildJsonObject {
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    })
                })
                put("final", "proxy")
                put("default_domain_resolver", "bootstrap-dns")
                // Required with Android VpnService auto-route. It activates the
                // typed platform callback that protects each proxy/upstream
                // socket from being captured by the same TUN again.
                put("auto_detect_interface", true)
            })
        }
        return formatter.encodeToString(JsonObject.serializer(), root)
    }

    private fun tlsObject(tls: TlsOptions): JsonObject = buildJsonObject {
        put("enabled", true)
        tls.serverName?.let { put("server_name", it) }
        if (!tls.fingerprint.isNullOrBlank()) {
            put("utls", buildJsonObject {
                put("enabled", true)
                put("fingerprint", tls.fingerprint)
            })
        }
        if (tls.alpn.isNotEmpty()) {
            put("alpn", buildJsonArray { tls.alpn.forEach { add(JsonPrimitive(it)) } })
        }
        if (tls.realityEnabled) {
            put("reality", buildJsonObject {
                put("enabled", true)
                put("public_key", requireNotNull(tls.realityPublicKey))
                tls.realityShortId?.let { put("short_id", it) }
            })
        }
    }

    private fun transportObject(transport: TransportOptions): JsonObject? {
        if (transport.type == TransportType.TCP) return null
        if (transport.type == TransportType.SPLIT_HTTP || transport.type == TransportType.MKCP) {
            // sing-box 1.14 does not implement XHTTP or mKCP. Retain imported
            // profiles for display/export, but never generate invalid native JSON.
            throw UnsupportedEngineProfileException("transport_not_supported_by_libbox_1_14")
        }
        return buildJsonObject {
            put("type", transport.type.wireName)
            when (transport.type) {
                TransportType.HTTP -> {
                    transport.path?.let { put("path", it) }
                    transport.host?.let { put("host", it) }
                }
                TransportType.WEBSOCKET,
                TransportType.SPLIT_HTTP -> {
                    transport.path?.let { put("path", it) }
                    transport.host?.let { host ->
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                }
                TransportType.HTTP_UPGRADE -> {
                    transport.path?.let { put("path", it) }
                    transport.host?.let { put("host", it) }
                }
                TransportType.GRPC -> {
                    transport.serviceName?.let { put("service_name", it) }
                }
                TransportType.QUIC,
                TransportType.MKCP,
                TransportType.TCP -> Unit
            }
        }
    }
}
