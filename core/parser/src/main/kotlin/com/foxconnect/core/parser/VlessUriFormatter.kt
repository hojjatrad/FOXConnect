package com.foxconnect.core.parser

import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.VlessProfile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object VlessUriFormatter {
    fun format(profile: VlessProfile): String {
        require(profile.port in 1..65_535) { "Invalid VLESS port" }
        require(profile.host.isNotBlank()) { "Missing VLESS host" }
        val host = if (':' in profile.host && !profile.host.startsWith('[')) "[${profile.host}]" else profile.host
        val query = linkedMapOf<String, String>()
        if (profile.tls.enabled) {
            query["security"] = if (profile.tls.realityEnabled) "reality" else "tls"
            profile.tls.serverName?.takeIf(String::isNotBlank)?.let { query["sni"] = it }
            profile.tls.fingerprint?.takeIf(String::isNotBlank)?.let { query["fp"] = it }
            profile.tls.alpn.takeIf(List<String>::isNotEmpty)?.let { query["alpn"] = it.joinToString(",") }
            if (profile.tls.realityEnabled) {
                profile.tls.realityPublicKey?.takeIf(String::isNotBlank)?.let { query["pbk"] = it }
                profile.tls.realityShortId?.takeIf(String::isNotBlank)?.let { query["sid"] = it }
            }
        }
        profile.flow?.takeIf(String::isNotBlank)?.let { query["flow"] = it }
        profile.packetEncoding?.takeIf(String::isNotBlank)?.let { query["packetEncoding"] = it }
        if (profile.transport.type != TransportType.TCP) query["type"] = profile.transport.type.wireName
        profile.transport.path?.takeIf(String::isNotBlank)?.let { query["path"] = it }
        profile.transport.host?.takeIf(String::isNotBlank)?.let { query["host"] = it }
        profile.transport.serviceName?.takeIf(String::isNotBlank)?.let { query["serviceName"] = it }
        profile.transport.authority?.takeIf(String::isNotBlank)?.let { query["authority"] = it }

        return buildString {
            append("vless://")
            append(profile.uuid.lowercase())
            append('@')
            append(host)
            append(':')
            append(profile.port)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" })
            }
            profile.name.takeIf(String::isNotBlank)?.let {
                append('#')
                append(encode(it))
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}
