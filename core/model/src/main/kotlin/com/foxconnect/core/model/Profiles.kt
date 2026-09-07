package com.foxconnect.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransportType(val wireName: String) {
    TCP("tcp"),
    HTTP("http"),
    WEBSOCKET("ws"),
    QUIC("quic"),
    GRPC("grpc"),
    HTTP_UPGRADE("httpupgrade"),
    SPLIT_HTTP("xhttp"),
    MKCP("kcp")
}

@Serializable
data class TlsOptions(
    val enabled: Boolean = false,
    val realityEnabled: Boolean = false,
    val serverName: String? = null,
    val fingerprint: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val alpn: List<String> = emptyList(),
)

@Serializable
data class TransportOptions(
    val type: TransportType = TransportType.TCP,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    val authority: String? = null,
)

sealed interface ConnectableProfile {
    val id: String
    val name: String
    val host: String
    val port: Int
    val protocol: ProtocolType
}

@Serializable
data class VlessProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val uuid: String,
    val flow: String? = null,
    val packetEncoding: String? = null,
    val tls: TlsOptions = TlsOptions(),
    val transport: TransportOptions = TransportOptions(),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.VLESS
}

@Serializable
data class VmessProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val uuid: String,
    val security: String = "auto",
    val packetEncoding: String? = null,
    val tls: TlsOptions = TlsOptions(),
    val transport: TransportOptions = TransportOptions(),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.VMESS
}

@Serializable
data class TrojanProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val password: String,
    val tls: TlsOptions = TlsOptions(enabled = true),
    val transport: TransportOptions = TransportOptions(),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.TROJAN
}

@Serializable
data class ShadowsocksProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val method: String,
    val password: String,
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.SHADOWSOCKS
}

@Serializable
data class Hysteria2Profile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val password: String,
    val upMbps: Int? = null,
    val downMbps: Int? = null,
    val obfsType: String? = null,
    val obfsPassword: String? = null,
    val tls: TlsOptions = TlsOptions(enabled = true),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.HYSTERIA2
}

@Serializable
data class HysteriaProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val auth: String? = null,
    val obfs: String? = null,
    val upMbps: Int,
    val downMbps: Int,
    val tls: TlsOptions = TlsOptions(enabled = true),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.HYSTERIA
}

@Serializable
data class TuicProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val uuid: String,
    val password: String? = null,
    val congestionControl: String = "cubic",
    val udpRelayMode: String = "native",
    val tls: TlsOptions = TlsOptions(enabled = true),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.TUIC
}

@Serializable
data class AnyTlsProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val password: String,
    val tls: TlsOptions = TlsOptions(enabled = true),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.ANYTLS
}

@Serializable
data class WireGuardPeer(
    val address: String,
    val port: Int,
    val publicKey: String,
    val preSharedKey: String? = null,
    val allowedIps: List<String>,
    val persistentKeepaliveSeconds: Int? = null,
    val reserved: List<Int> = emptyList(),
)

@Serializable
data class WireGuardProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val privateKey: String,
    val localAddresses: List<String>,
    val peers: List<WireGuardPeer>,
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = 1408,
    val listenPort: Int? = null,
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.WIREGUARD
}

@Serializable
data class Socks5Profile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val username: String? = null,
    val password: String? = null,
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.SOCKS5
}

@Serializable
data class HttpProxyProfile(
    override val id: String,
    override val name: String,
    override val host: String,
    override val port: Int,
    val username: String? = null,
    val password: String? = null,
    val path: String? = null,
    val tls: TlsOptions = TlsOptions(),
) : ConnectableProfile {
    override val protocol: ProtocolType = ProtocolType.HTTP
}
