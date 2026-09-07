package com.foxconnect.core.parser

import com.foxconnect.core.model.TlsOptions
import com.foxconnect.core.model.TransportOptions
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.VlessProfile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object VlessUriParser {
    fun parse(raw: String): ParseResult<VlessProfile> {
        val uri = try {
            URI(raw.trim())
        } catch (error: Exception) {
            return ParseResult.Error("invalid_uri", "ساختار لینک VLESS معتبر نیست")
        }
        if (!uri.scheme.equals("vless", ignoreCase = true)) {
            return ParseResult.Error("wrong_scheme", "این لینک از نوع VLESS نیست", uri.scheme)
        }
        val uuid = decode(uri.userInfo?.substringBefore(':').orEmpty())
        if (!isUuid(uuid)) {
            return ParseResult.Error("invalid_uuid", "شناسهٔ UUID در کانفیگ VLESS معتبر نیست")
        }
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank)
            ?: return ParseResult.Error("missing_host", "آدرس سرور در کانفیگ VLESS وجود ندارد")
        val port = uri.port
        if (port !in 1..65535) {
            return ParseResult.Error("invalid_port", "پورت سرور باید بین ۱ تا ۶۵۵۳۵ باشد")
        }
        val query = parseQuery(uri.rawQuery)
        val transport = when (query["type"]?.lowercase()) {
            null, "", "tcp", "raw" -> TransportType.TCP
            "http", "h2" -> TransportType.HTTP
            "ws", "websocket" -> TransportType.WEBSOCKET
            "quic" -> TransportType.QUIC
            "grpc" -> TransportType.GRPC
            "httpupgrade" -> TransportType.HTTP_UPGRADE
            "xhttp", "splithttp" -> TransportType.SPLIT_HTTP
            "kcp", "mkcp" -> TransportType.MKCP
            else -> return ParseResult.Error(
                "unsupported_transport",
                "نوع انتقال VLESS پشتیبانی نمی‌شود",
                query["type"],
            )
        }
        val quicSecurity = query["quicSecurity"] ?: query["quic_security"]
        if (transport == TransportType.QUIC && !quicSecurity.isNullOrBlank() &&
            !quicSecurity.equals("none", ignoreCase = true)
        ) {
            return ParseResult.Error(
                "unsupported_quic_encryption",
                "رمزنگاری افزودهٔ QUIC با هستهٔ VPN سازگار نیست",
            )
        }
        val security = query["security"]?.lowercase().orEmpty()
        if (security !in setOf("", "none", "tls", "xtls", "reality")) {
            return ParseResult.Error("unsupported_security", "نوع امنیت VLESS پشتیبانی نمی‌شود", security)
        }
        val reality = security == "reality"
        val tls = security == "tls" || security == "xtls" || reality
        if (reality && query["pbk"].isNullOrBlank()) {
            return ParseResult.Error("missing_reality_key", "کلید عمومی Reality در کانفیگ وجود ندارد")
        }
        if (reality && !query["pbk"].orEmpty().matches(Regex("[A-Za-z0-9_-]{43}"))) {
            return ParseResult.Error("invalid_reality_key", "کلید عمومی Reality معتبر نیست")
        }
        query["sid"]?.takeIf(String::isNotBlank)?.let { shortId ->
            if (shortId.length > 16 || shortId.length % 2 != 0 || !shortId.matches(Regex("[0-9a-fA-F]+"))) {
                return ParseResult.Error("invalid_reality_short_id", "Short ID در Reality معتبر نیست")
            }
        }
        val name = decode(uri.rawFragment.orEmpty()).ifBlank { "$host:$port" }
        val tlsOptions = TlsOptions(
            enabled = tls,
            realityEnabled = reality,
            serverName = query["sni"]?.takeIf { tls && it.isNotBlank() },
            fingerprint = query["fp"]?.takeIf { tls && it.isNotBlank() },
            realityPublicKey = query["pbk"]?.takeIf { reality && it.isNotBlank() },
            realityShortId = query["sid"]?.takeIf { reality && it.isNotBlank() },
            alpn = if (tls) {
                query["alpn"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            } else {
                emptyList()
            },
        )
        val transportOptions = TransportOptions(
            type = transport,
            path = query["path"]?.takeIf(String::isNotBlank),
            host = query["host"]?.takeIf(String::isNotBlank),
            serviceName = query["serviceName"]?.takeIf(String::isNotBlank),
            authority = query["authority"]?.takeIf(String::isNotBlank),
        )
        val stableId = stableId(
            host = host,
            port = port,
            uuid = uuid,
            flow = query["flow"],
            packetEncoding = query["packetEncoding"] ?: query["packet_encoding"],
            tls = tlsOptions,
            transport = transportOptions,
        )
        return ParseResult.Success(
            VlessProfile(
                id = stableId,
                name = name,
                host = host,
                port = port,
                uuid = uuid.lowercase(),
                flow = query["flow"]?.takeIf(String::isNotBlank),
                packetEncoding = query["packetEncoding"] ?: query["packet_encoding"],
                tls = tlsOptions,
                transport = transportOptions,
            ),
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private fun stableId(
        host: String,
        port: Int,
        uuid: String,
        flow: String?,
        packetEncoding: String?,
        tls: TlsOptions,
        transport: TransportOptions,
    ): String = sha256(
        listOf(
            host.lowercase(), port.toString(), uuid.lowercase(), flow.orEmpty(), packetEncoding.orEmpty(),
            tls.enabled.toString(), tls.realityEnabled.toString(), tls.serverName.orEmpty().lowercase(),
            tls.fingerprint.orEmpty().lowercase(), tls.realityPublicKey.orEmpty(), tls.realityShortId.orEmpty(),
            tls.alpn.joinToString(",") { it.lowercase() }, transport.type.wireName,
            transport.path.orEmpty(), transport.host.orEmpty().lowercase(), transport.serviceName.orEmpty(),
            transport.authority.orEmpty().lowercase(),
        ).joinToString("\u001f"),
    ).take(24)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
