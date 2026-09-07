package com.foxconnect.core.parser

import com.foxconnect.core.model.ShadowsocksProfile
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Strict SIP002 and legacy Shadowsocks URI parser. */
object ShadowsocksUriParser {
    private val supportedMethods = setOf(
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
    )

    fun parse(raw: String): ParseResult<ShadowsocksProfile> {
        val text = raw.trim()
        if (!text.startsWith("ss://", ignoreCase = true)) {
            return ParseResult.Error("wrong_scheme", "این لینک از نوع Shadowsocks نیست")
        }
        if (text.length > MAX_URI_CHARS) {
            return ParseResult.Error("uri_too_large", "لینک Shadowsocks بیش از حد طولانی است")
        }
        val withoutScheme = text.substringAfter("://")
        val withoutFragment = withoutScheme.substringBefore('#')
        val authority = withoutFragment.substringBefore('?')
        val rawQuery = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (parseQuery(rawQuery)["plugin"].orEmpty().isNotBlank()) {
            return ParseResult.Error("unsupported_plugin", "افزونهٔ Shadowsocks در این نسخه پشتیبانی نمی‌شود")
        }

        val decodedAuthority = if ('@' in authority) {
            authority
        } else {
            decodeBase64Text(authority)
                ?: return ParseResult.Error("invalid_credentials", "اطلاعات ورود Shadowsocks معتبر نیست")
        }
        val separator = decodedAuthority.lastIndexOf('@')
        if (separator <= 0 || separator == decodedAuthority.lastIndex) {
            return ParseResult.Error("invalid_credentials", "اطلاعات ورود Shadowsocks معتبر نیست")
        }
        val rawCredentials = decodedAuthority.substring(0, separator)
        val endpoint = decodedAuthority.substring(separator + 1)
        val credentials = if (':' in percentDecode(rawCredentials)) {
            percentDecode(rawCredentials)
        } else {
            decodeBase64Text(rawCredentials)
                ?: return ParseResult.Error("invalid_credentials", "اطلاعات ورود Shadowsocks معتبر نیست")
        }
        val method = credentials.substringBefore(':').lowercase()
        val password = credentials.substringAfter(':', missingDelimiterValue = "")
        if (method !in supportedMethods) {
            return ParseResult.Error("unsupported_method", "روش رمزنگاری Shadowsocks امن یا پشتیبانی‌شده نیست", method)
        }
        if (password.isEmpty() || password.length > MAX_PASSWORD_CHARS || password.any(Char::isISOControl)) {
            return ParseResult.Error("invalid_password", "رمز Shadowsocks وجود ندارد یا معتبر نیست")
        }

        val endpointUri = try {
            URI("ss://placeholder@$endpoint")
        } catch (error: Exception) {
            return ParseResult.Error("invalid_uri", "ساختار لینک Shadowsocks معتبر نیست")
        }
        if (!endpointUri.rawPath.isNullOrEmpty() || endpointUri.rawQuery != null || endpointUri.rawFragment != null) {
            return ParseResult.Error("invalid_uri", "ساختار مقصد Shadowsocks معتبر نیست")
        }
        val host = endpointUri.host?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank)
            ?: return ParseResult.Error("missing_host", "آدرس سرور در کانفیگ Shadowsocks وجود ندارد")
        val port = endpointUri.port
        if (port !in 1..65535) {
            return ParseResult.Error("invalid_port", "پورت سرور باید بین ۱ تا ۶۵۵۳۵ باشد")
        }
        val name = percentDecode(withoutScheme.substringAfterLast('#', missingDelimiterValue = ""))
            .ifBlank { "$host:$port" }
            .take(120)
        return ParseResult.Success(
            ShadowsocksProfile(
                id = stableId(host, port, method, password),
                name = name,
                host = host,
                port = port,
                method = method,
                password = password,
            ),
        )
    }

    private fun parseQuery(raw: String): Map<String, String> = raw
        .split('&')
        .filter(String::isNotBlank)
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            percentDecode(pieces[0]).lowercase() to percentDecode(pieces.getOrElse(1) { "" })
        }

    private fun decodeBase64Text(value: String): String? {
        val compact = value.trim()
        if (compact.isEmpty() || compact.length > MAX_URI_CHARS || !compact.matches(BASE64_CHARS)) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val decoded = sequenceOf(Base64.getUrlDecoder(), Base64.getDecoder())
            .mapNotNull { decoder -> runCatching { decoder.decode(padded) }.getOrNull() }
            .firstOrNull() ?: return null
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        }.getOrNull()
    }

    private fun percentDecode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun stableId(host: String, port: Int, method: String, password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(listOf(host.lowercase(), port.toString(), method, password).joinToString("\u001f").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)

    private const val MAX_URI_CHARS = 16 * 1024
    private const val MAX_PASSWORD_CHARS = 1_024
    private val BASE64_CHARS = Regex("[A-Za-z0-9_+/=-]+")
}
