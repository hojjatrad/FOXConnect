package com.foxconnect.core.parser

import com.foxconnect.core.model.TlsOptions
import com.foxconnect.core.model.TransportOptions
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.TrojanProfile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object TrojanUriParser {
    private const val MAX_PASSWORD_CHARS = 1_024

    fun parse(raw: String): ParseResult<TrojanProfile> {
        val uri = try {
            URI(raw.trim())
        } catch (error: Exception) {
            return ParseResult.Error("invalid_uri", "ساختار لینک Trojan معتبر نیست")
        }
        if (!uri.scheme.equals("trojan", ignoreCase = true)) {
            return ParseResult.Error("wrong_scheme", "این لینک از نوع Trojan نیست", uri.scheme)
        }
        val password = decode(uri.rawUserInfo.orEmpty())
        if (password.isBlank() || password.length > MAX_PASSWORD_CHARS) {
            return ParseResult.Error("invalid_password", "رمز Trojan وجود ندارد یا بیش از حد طولانی است")
        }
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank)
            ?: return ParseResult.Error("missing_host", "آدرس سرور در کانفیگ Trojan وجود ندارد")
        val port = uri.port
        if (port !in 1..65535) {
            return ParseResult.Error("invalid_port", "پورت سرور باید بین ۱ تا ۶۵۵۳۵ باشد")
        }
        val query = parseQuery(uri.rawQuery)
        if (
            query["allowinsecure"].isTrue() || query["allow_insecure"].isTrue() ||
            query["insecure"].isTrue() || query["skip-cert-verify"].isTrue()
        ) {
            return ParseResult.Error("insecure_tls_forbidden", "نادیده‌گرفتن اعتبار TLS مجاز نیست")
        }
        val security = query["security"]?.lowercase().orEmpty()
        if (security !in setOf("", "tls", "reality")) {
            return ParseResult.Error("unsupported_security", "Trojan فقط با TLS معتبر پذیرفته می‌شود", security)
        }
        val reality = security == "reality"
        if (reality && !query["pbk"].orEmpty().matches(Regex("[A-Za-z0-9_-]{43}"))) {
            return ParseResult.Error("invalid_reality_key", "کلید عمومی Reality معتبر نیست")
        }
        query["sid"]?.takeIf(String::isNotBlank)?.let { shortId ->
            if (shortId.length > 16 || shortId.length % 2 != 0 || !shortId.matches(Regex("[0-9a-fA-F]+"))) {
                return ParseResult.Error("invalid_reality_short_id", "Short ID در Reality معتبر نیست")
            }
        }
        val transportType = when (query["type"]?.lowercase()) {
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
                "نوع انتقال Trojan پشتیبانی نمی‌شود",
                query["type"],
            )
        }
        val quicSecurity = query["quicsecurity"] ?: query["quic_security"]
        if (transportType == TransportType.QUIC && !quicSecurity.isNullOrBlank() &&
            !quicSecurity.equals("none", ignoreCase = true)
        ) {
            return ParseResult.Error(
                "unsupported_quic_encryption",
                "رمزنگاری افزودهٔ QUIC با هستهٔ VPN سازگار نیست",
            )
        }
        val tls = TlsOptions(
            enabled = true,
            realityEnabled = reality,
            serverName = query["sni"]?.takeIf(String::isNotBlank)
                ?: query["peer"]?.takeIf(String::isNotBlank),
            fingerprint = query["fp"]?.takeIf(String::isNotBlank),
            realityPublicKey = query["pbk"]?.takeIf { reality && it.isNotBlank() },
            realityShortId = query["sid"]?.takeIf { reality && it.isNotBlank() },
            alpn = query["alpn"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        )
        val transport = TransportOptions(
            type = transportType,
            path = query["path"]?.takeIf(String::isNotBlank),
            host = query["host"]?.takeIf(String::isNotBlank),
            serviceName = (query["servicename"] ?: query["service_name"])?.takeIf(String::isNotBlank),
            authority = query["authority"]?.takeIf(String::isNotBlank),
        )
        val name = decode(uri.rawFragment.orEmpty()).ifBlank { "$host:$port" }.take(120)
        return ParseResult.Success(
            TrojanProfile(
                id = stableId(host, port, password, tls, transport),
                name = name,
                host = host,
                port = port,
                password = password,
                tls = tls,
                transport = transport,
            ),
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]).lowercase() to decode(pieces.getOrElse(1) { "" })
        }

    private fun stableId(
        host: String,
        port: Int,
        password: String,
        tls: TlsOptions,
        transport: TransportOptions,
    ): String = sha256(
        listOf(
            host.lowercase(), port.toString(), password, tls.realityEnabled.toString(),
            tls.serverName.orEmpty().lowercase(), tls.fingerprint.orEmpty().lowercase(),
            tls.realityPublicKey.orEmpty(), tls.realityShortId.orEmpty(),
            tls.alpn.joinToString(",") { it.lowercase() }, transport.type.wireName,
            transport.path.orEmpty(), transport.host.orEmpty().lowercase(),
            transport.serviceName.orEmpty(), transport.authority.orEmpty().lowercase(),
        ).joinToString("\u001f"),
    ).take(24)

    private fun String?.isTrue(): Boolean = this?.lowercase() in setOf("1", "true", "yes")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
