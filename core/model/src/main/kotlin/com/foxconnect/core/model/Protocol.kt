package com.foxconnect.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProtocolType(val scheme: String, val displayName: String) {
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("ss", "Shadowsocks"),
    HYSTERIA2("hy2", "Hysteria 2"),
    HYSTERIA("hysteria", "Hysteria"),
    TUIC("tuic", "TUIC v5"),
    ANYTLS("anytls", "anyTLS"),
    WIREGUARD("wg", "WireGuard"),
    SOCKS5("socks", "SOCKS5"),
    HTTP("http", "HTTP");

    companion object {
        fun fromScheme(scheme: String?): ProtocolType? = entries.firstOrNull {
            it.scheme.equals(scheme, ignoreCase = true) ||
                (it == HYSTERIA2 && scheme.equals("hysteria2", ignoreCase = true)) ||
                (it == WIREGUARD && scheme.equals("wireguard", ignoreCase = true)) ||
                (it == SOCKS5 && scheme.equals("socks5", ignoreCase = true)) ||
                (it == HTTP && scheme.equals("https", ignoreCase = true))
        }
    }
}
