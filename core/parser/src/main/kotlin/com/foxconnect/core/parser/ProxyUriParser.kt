package com.foxconnect.core.parser

import com.foxconnect.core.model.HttpProxyProfile
import com.foxconnect.core.model.Socks5Profile
import com.foxconnect.core.model.TlsOptions
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object Socks5UriParser {
    fun parse(raw: String): ParseResult<Socks5Profile> {
        val uri = parseUri(raw, setOf("socks", "socks5")) ?: return ParseResult.Error(
            "invalid_uri",
            "ساختار لینک SOCKS5 معتبر نیست",
        )
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) {
            return ParseResult.Error("invalid_uri", "لینک SOCKS5 نباید path یا query داشته باشد")
        }
        val endpoint = endpoint(uri, "SOCKS5")
        if (endpoint is ParseResult.Error) return endpoint
        endpoint as ParseResult.Success
        val credentials = credentials(uri.rawUserInfo, "SOCKS5")
        if (credentials is ParseResult.Error) return credentials
        credentials as ParseResult.Success
        val (username, password) = credentials.value
        val name = percentDecode(uri.rawFragment.orEmpty()).ifBlank {
            "${endpoint.value.first}:${endpoint.value.second}"
        }.take(120)
        return ParseResult.Success(
            Socks5Profile(
                id = stableId("socks5", endpoint.value.first, endpoint.value.second.toString(), username, password),
                name = name,
                host = endpoint.value.first,
                port = endpoint.value.second,
                username = username,
                password = password,
            ),
        )
    }
}

object HttpProxyUriParser {
    fun parse(raw: String): ParseResult<HttpProxyProfile> {
        val uri = parseUri(raw, setOf("http", "https")) ?: return ParseResult.Error(
            "invalid_uri",
            "ساختار لینک پراکسی HTTP معتبر نیست",
        )
        val endpoint = endpoint(uri, "HTTP")
        if (endpoint is ParseResult.Error) return endpoint
        endpoint as ParseResult.Success
        val credentials = credentials(uri.rawUserInfo, "HTTP")
        if (credentials is ParseResult.Error) return credentials
        credentials as ParseResult.Success
        val query = parseQuery(uri.rawQuery)
        if (query.keys.any { it !in HTTP_QUERY_KEYS }) {
            return ParseResult.Error("unsupported_option", "گزینهٔ ناشناخته در پراکسی HTTP وجود دارد")
        }
        if (
            query["allowinsecure"].isTrue() || query["allow_insecure"].isTrue() ||
            query["insecure"].isTrue() || query["skip-cert-verify"].isTrue()
        ) {
            return ParseResult.Error("insecure_tls_forbidden", "نادیده‌گرفتن اعتبار TLS مجاز نیست")
        }
        val tlsEnabled = uri.scheme.equals("https", ignoreCase = true)
        if (!tlsEnabled && query.keys.any { it in TLS_QUERY_KEYS }) {
            return ParseResult.Error("tls_option_without_tls", "گزینهٔ TLS برای پراکسی HTTP بدون TLS معتبر نیست")
        }
        val path = percentDecode(uri.rawPath.orEmpty()).takeIf { it.isNotEmpty() && it != "/" }
        if (path != null && (path.length > MAX_PATH_CHARS || !path.startsWith('/'))) {
            return ParseResult.Error("invalid_path", "مسیر پراکسی HTTP معتبر نیست")
        }
        val tls = TlsOptions(
            enabled = tlsEnabled,
            serverName = query["sni"]?.takeIf(String::isNotBlank),
            fingerprint = query["fp"]?.takeIf(String::isNotBlank),
            alpn = query["alpn"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        )
        val (username, password) = credentials.value
        val name = percentDecode(uri.rawFragment.orEmpty()).ifBlank {
            "${endpoint.value.first}:${endpoint.value.second}"
        }.take(120)
        return ParseResult.Success(
            HttpProxyProfile(
                id = stableId(
                    if (tlsEnabled) "https" else "http",
                    endpoint.value.first,
                    endpoint.value.second.toString(),
                    username,
                    password,
                    path.orEmpty(),
                    tls.serverName.orEmpty(),
                    tls.fingerprint.orEmpty(),
                    tls.alpn.joinToString(","),
                ),
                name = name,
                host = endpoint.value.first,
                port = endpoint.value.second,
                username = username,
                password = password,
                path = path,
                tls = tls,
            ),
        )
    }

    private val HTTP_QUERY_KEYS = setOf(
        "sni", "fp", "alpn", "allowinsecure", "allow_insecure", "insecure", "skip-cert-verify",
    )
    private val TLS_QUERY_KEYS = HTTP_QUERY_KEYS
}

private fun parseUri(raw: String, schemes: Set<String>): URI? {
    val text = raw.trim()
    if (text.length > MAX_PROXY_URI_CHARS) return null
    return runCatching { URI(text) }.getOrNull()?.takeIf {
        it.scheme?.lowercase() in schemes && it.host != null && it.port in 1..65535
    }
}

private fun endpoint(uri: URI, label: String): ParseResult<Pair<String, Int>> {
    val host = uri.host?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank)
        ?: return ParseResult.Error("missing_host", "آدرس سرور در کانفیگ $label وجود ندارد")
    val port = uri.port
    if (port !in 1..65535) {
        return ParseResult.Error("invalid_port", "پورت سرور باید بین ۱ تا ۶۵۵۳۵ باشد")
    }
    return ParseResult.Success(host to port)
}

private fun credentials(rawUserInfo: String?, label: String): ParseResult<Pair<String?, String?>> {
    if (rawUserInfo == null) return ParseResult.Success(null to null)
    if (':' !in rawUserInfo) {
        return ParseResult.Error("invalid_credentials", "اطلاعات ورود $label باید شامل نام کاربری و رمز باشد")
    }
    val username = percentDecode(rawUserInfo.substringBefore(':'))
    val password = percentDecode(rawUserInfo.substringAfter(':'))
    if (
        username.isEmpty() || username.length > MAX_PROXY_CREDENTIAL_CHARS ||
        password.length > MAX_PROXY_CREDENTIAL_CHARS ||
        username.any(Char::isISOControl) || password.any(Char::isISOControl)
    ) {
        return ParseResult.Error("invalid_credentials", "اطلاعات ورود $label معتبر نیست")
    }
    return ParseResult.Success(username to password)
}

private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty()
    .split('&')
    .filter(String::isNotBlank)
    .associate { part ->
        val pieces = part.split('=', limit = 2)
        percentDecode(pieces[0]).lowercase() to percentDecode(pieces.getOrElse(1) { "" })
    }

private fun String?.isTrue(): Boolean = this?.lowercase() in setOf("1", "true", "yes")

private fun percentDecode(value: String): String = runCatching {
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private fun stableId(vararg parts: String?): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u001f") { it.orEmpty() }.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
    .take(24)

private const val MAX_PROXY_URI_CHARS = 16 * 1024
private const val MAX_PROXY_CREDENTIAL_CHARS = 1_024
private const val MAX_PATH_CHARS = 2_048
