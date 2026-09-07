package com.foxconnect.core.parser

import com.foxconnect.core.model.ConnectableProfile
import com.foxconnect.core.model.ProtocolType

/** Dispatches only protocols whose parser and engine mapping are enabled. */
object ConnectableProfileParser {
    fun parse(raw: String): ParseResult<ConnectableProfile> = when (val detected = ConfigDetector.detect(raw)) {
        is ParseResult.Error -> detected
        is ParseResult.Success -> when (detected.value) {
            ProtocolType.VLESS -> VlessUriParser.parse(raw)
            ProtocolType.VMESS -> VmessUriParser.parse(raw)
            ProtocolType.TROJAN -> TrojanUriParser.parse(raw)
            ProtocolType.SHADOWSOCKS -> ShadowsocksUriParser.parse(raw)
            ProtocolType.HYSTERIA2 -> Hysteria2UriParser.parse(raw)
            ProtocolType.HYSTERIA -> HysteriaUriParser.parse(raw)
            ProtocolType.TUIC -> TuicUriParser.parse(raw)
            ProtocolType.ANYTLS -> AnyTlsUriParser.parse(raw)
            ProtocolType.WIREGUARD -> WireGuardConfigParser.parse(raw)
            ProtocolType.SOCKS5 -> Socks5UriParser.parse(raw)
            ProtocolType.HTTP -> HttpProxyUriParser.parse(raw)
        }
    }
}
