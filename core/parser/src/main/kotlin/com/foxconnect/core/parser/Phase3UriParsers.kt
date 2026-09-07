package com.foxconnect.core.parser

import com.foxconnect.core.model.AnyTlsProfile
import com.foxconnect.core.model.Hysteria2Profile
import com.foxconnect.core.model.HysteriaProfile
import com.foxconnect.core.model.TlsOptions
import com.foxconnect.core.model.TransportOptions
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.TuicProfile
import com.foxconnect.core.model.VmessProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Strict parser for the de-facto v2rayN `vmess://base64(JSON)` format. */
object VmessUriParser {
    private const val MAX_URI_CHARS = 16 * 1024
    private const val MAX_DECODED_BYTES = 16 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(raw: String): ParseResult<VmessProfile> {
        val text = raw.trim()
        if (!text.startsWith("vmess://", ignoreCase = true)) {
            return ParseResult.Error("wrong_scheme", "این لینک از نوع VMess نیست")
        }
        if (text.length > MAX_URI_CHARS) {
            return ParseResult.Error("uri_too_long", "لینک VMess بیش از حد طولانی است")
        }
        val payload = text.substringAfter("://")
        if (payload.isBlank()) return ParseResult.Error("invalid_vmess_payload", "محتوای VMess وجود ندارد")
        val decoded = decodeBase64(payload)
            ?: return ParseResult.Error("invalid_vmess_payload", "محتوای Base64 کانفیگ VMess معتبر نیست")
        val root = runCatching {
            val content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
            json.parseToJsonElement(content) as JsonObject
        }.getOrElse {
            return ParseResult.Error("invalid_vmess_json", "محتوای JSON کانفیگ VMess معتبر نیست", it.message)
        }
        val known = setOf(
            "v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path",
            "tls", "sni", "alpn", "fp", "allowInsecure", "allow_insecure", "insecure",
            "packetEncoding", "packet_encoding", "pbk", "sid", "authority", "serviceName",
        )
        root.keys.firstOrNull { it !in known }?.let {
            return ParseResult.Error("unsupported_option", "گزینهٔ پشتیبانی‌نشده در VMess وجود دارد", it)
        }
        fun field(name: String): String? = (root[name] as? JsonPrimitive)?.contentOrNull
        fun bool(name: String): Boolean = (root[name] as? JsonPrimitive)?.let { primitive ->
            primitive.booleanOrNull ?: primitive.contentOrNull.isTrue()
        } == true

        if (field("v")?.trim() !in setOf(null, "", "2")) {
            return ParseResult.Error("unsupported_version", "فقط قالب نسخهٔ ۲ لینک VMess پشتیبانی می‌شود")
        }
        val host = field("add")?.trim()?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank)
            ?: return ParseResult.Error("missing_host", "آدرس سرور در کانفیگ VMess وجود ندارد")
        if (host.length > 253 || host.any(Char::isWhitespace)) {
            return ParseResult.Error("invalid_host", "آدرس سرور VMess معتبر نیست")
        }
        val port = field("port")?.toIntOrNull()
            ?: return ParseResult.Error("invalid_port", "پورت سرور باید بین ۱ تا ۶۵۵۳۵ باشد")
        if (port !in 1..65535) return ParseResult.Error("invalid_port", "پورت سرور باید بین ۱ تا ۶۵۵۳۵ باشد")
        val uuid = field("id")?.let(::canonicalUuid)
            ?: return ParseResult.Error("invalid_uuid", "شناسهٔ UUID در کانفیگ VMess معتبر نیست")
        val alterId = field("aid").orEmpty().ifBlank { "0" }.toIntOrNull()
        if (alterId != 0) {
            return ParseResult.Error("legacy_vmess_forbidden", "VMess legacy با Alter ID غیرصفر پذیرفته نمی‌شود")
        }
        val security = field("scy").orEmpty().ifBlank { "auto" }.lowercase()
        if (security !in setOf("auto", "aes-128-gcm", "chacha20-poly1305")) {
            return ParseResult.Error("unsupported_security", "روش رمزنگاری VMess امن یا پشتیبانی‌شده نیست", security)
        }
        if (bool("allowInsecure") || bool("allow_insecure") || bool("insecure")) {
            return ParseResult.Error("insecure_tls_forbidden", "نادیده‌گرفتن اعتبار TLS مجاز نیست")
        }
        val tlsMode = field("tls").orEmpty().lowercase()
        if (tlsMode !in setOf("", "none", "tls", "reality")) {
            return ParseResult.Error("unsupported_tls", "نوع TLS در کانفیگ VMess پشتیبانی نمی‌شود", tlsMode)
        }
        val reality = tlsMode == "reality"
        val realityKey = field("pbk")
        if (reality && !realityKey.orEmpty().matches(Regex("[A-Za-z0-9_-]{43}"))) {
            return ParseResult.Error("invalid_reality_key", "کلید عمومی Reality معتبر نیست")
        }
        val shortId = field("sid")?.takeIf(String::isNotBlank)
        if (shortId != null && (shortId.length > 16 || shortId.length % 2 != 0 || !shortId.matches(Regex("[0-9a-fA-F]+")))) {
            return ParseResult.Error("invalid_reality_short_id", "Short ID در Reality معتبر نیست")
        }
        val transportType = when (field("net")?.lowercase()) {
            null, "", "tcp", "raw" -> TransportType.TCP
            "http", "h2" -> TransportType.HTTP
            "ws", "websocket" -> TransportType.WEBSOCKET
            "quic" -> TransportType.QUIC
            "grpc", "gun" -> TransportType.GRPC
            "httpupgrade" -> TransportType.HTTP_UPGRADE
            "xhttp", "splithttp" -> TransportType.SPLIT_HTTP
            "kcp", "mkcp" -> TransportType.MKCP
            else -> return ParseResult.Error("unsupported_transport", "نوع انتقال VMess پشتیبانی نمی‌شود", field("net"))
        }
        val headerType = field("type").orEmpty().lowercase()
        if (transportType == TransportType.TCP && headerType !in setOf("", "none")) {
            return ParseResult.Error("unsupported_transport_header", "هدر جعلی TCP در VMess پشتیبانی نمی‌شود", headerType)
        }
        if (transportType == TransportType.QUIC && headerType !in setOf("", "none")) {
            return ParseResult.Error(
                "unsupported_quic_encryption",
                "رمزنگاری افزودهٔ QUIC با هستهٔ VPN سازگار نیست",
            )
        }
        val packetEncoding = (field("packetEncoding") ?: field("packet_encoding"))
            ?.takeIf(String::isNotBlank)?.lowercase()
        if (packetEncoding != null && packetEncoding !in setOf("packetaddr", "xudp")) {
            return ParseResult.Error("unsupported_packet_encoding", "نوع packet encoding در VMess پشتیبانی نمی‌شود")
        }
        val tls = TlsOptions(
            enabled = tlsMode in setOf("tls", "reality"),
            realityEnabled = reality,
            serverName = field("sni")?.takeIf(String::isNotBlank),
            fingerprint = field("fp")?.takeIf(String::isNotBlank),
            realityPublicKey = realityKey?.takeIf { reality },
            realityShortId = shortId?.takeIf { reality },
            alpn = field("alpn").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        )
        val transport = TransportOptions(
            type = transportType,
            path = field("path")?.takeIf(String::isNotBlank),
            host = field("host")?.takeIf(String::isNotBlank),
            serviceName = (field("serviceName") ?: field("path"))?.takeIf {
                transportType == TransportType.GRPC && it.isNotBlank()
            },
            authority = field("authority")?.takeIf(String::isNotBlank),
        )
        val name = field("ps")?.trim()?.ifBlank { null }?.take(120) ?: "$host:$port"
        return ParseResult.Success(
            VmessProfile(
                id = stableId(
                    "vmess", host.lowercase(), port.toString(), uuid, security,
                    packetEncoding.orEmpty(), tls.identity(), transport.identity(),
                ),
                name = name,
                host = host,
                port = port,
                uuid = uuid,
                security = security,
                packetEncoding = packetEncoding,
                tls = tls,
                transport = transport,
            ),
        )
    }

    private fun decodeBase64(value: String): ByteArray? {
        val compact = value.filterNot(Char::isWhitespace)
        if (!compact.matches(Regex("[A-Za-z0-9_+/=-]+"))) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder())
            .mapNotNull { decoder -> runCatching { decoder.decode(padded) }.getOrNull() }
            .firstOrNull { it.isNotEmpty() && it.size <= MAX_DECODED_BYTES }
    }
}

object Hysteria2UriParser {
    fun parse(raw: String): ParseResult<Hysteria2Profile> {
        val parts = parseUri(raw, setOf("hy2", "hysteria2"), "Hysteria 2") ?: return uriError("Hysteria 2")
        val query = parts.query
        unsupported(query, HY2_OPTIONS)?.let { return it }
        if (query.requestsInsecureTls()) return insecureTlsError()
        val password = decode(parts.uri.rawUserInfo.orEmpty())
        if (password.isBlank() || password.length > MAX_SECRET_CHARS) {
            return ParseResult.Error("invalid_password", "رمز Hysteria 2 وجود ندارد یا بیش از حد طولانی است")
        }
        val obfsType = query["obfs"]?.lowercase()?.takeIf(String::isNotBlank)
        if (obfsType != null && obfsType !in setOf("salamander", "gecko")) {
            return ParseResult.Error("unsupported_obfs", "نوع obfuscation در Hysteria 2 پشتیبانی نمی‌شود")
        }
        val obfsPassword = (query["obfs-password"] ?: query["obfspassword"])?.takeIf(String::isNotBlank)
        if ((obfsType == null) != (obfsPassword == null)) {
            return ParseResult.Error("invalid_obfs", "نوع و رمز obfuscation باید با هم وارد شوند")
        }
        val up = positiveMbps(query["upmbps"]) ?: if (query.containsKey("upmbps")) return invalidBandwidth() else null
        val down = positiveMbps(query["downmbps"]) ?: if (query.containsKey("downmbps")) return invalidBandwidth() else null
        val tls = strictTls(query, parts.host)
        return ParseResult.Success(
            Hysteria2Profile(
                id = stableId("hy2", parts.host.lowercase(), parts.port.toString(), password, up.toString(), down.toString(), obfsType.orEmpty(), obfsPassword.orEmpty(), tls.identity()),
                name = parts.name,
                host = parts.host,
                port = parts.port,
                password = password,
                upMbps = up,
                downMbps = down,
                obfsType = obfsType,
                obfsPassword = obfsPassword,
                tls = tls,
            ),
        )
    }

    private val HY2_OPTIONS = setOf(
        "sni", "peer", "alpn", "fp", "insecure", "allowinsecure", "allow_insecure",
        "skip-cert-verify", "obfs", "obfs-password", "obfspassword", "upmbps", "downmbps",
    )
}

object HysteriaUriParser {
    fun parse(raw: String): ParseResult<HysteriaProfile> {
        val parts = parseUri(raw, setOf("hysteria"), "Hysteria") ?: return uriError("Hysteria")
        val query = parts.query
        unsupported(query, HY_OPTIONS)?.let { return it }
        if (query.requestsInsecureTls()) return insecureTlsError()
        val protocol = query["protocol"]?.lowercase().orEmpty()
        if (protocol !in setOf("", "udp")) {
            return ParseResult.Error("unsupported_transport", "فقط انتقال UDP در Hysteria پشتیبانی می‌شود")
        }
        val up = positiveMbps(query["upmbps"]) ?: return invalidBandwidth()
        val down = positiveMbps(query["downmbps"]) ?: return invalidBandwidth()
        val auth = (query["auth"] ?: query["auth_str"] ?: decode(parts.uri.rawUserInfo.orEmpty()))
            .takeIf(String::isNotBlank)
        if (auth?.length?.let { it > MAX_SECRET_CHARS } == true) {
            return ParseResult.Error("invalid_auth", "اطلاعات احراز هویت Hysteria بیش از حد طولانی است")
        }
        val obfs = query["obfs"]?.takeIf(String::isNotBlank)
        if (obfs?.length?.let { it > MAX_SECRET_CHARS } == true) {
            return ParseResult.Error("invalid_obfs", "رمز obfuscation بیش از حد طولانی است")
        }
        val tls = strictTls(query, parts.host)
        return ParseResult.Success(
            HysteriaProfile(
                id = stableId("hysteria", parts.host.lowercase(), parts.port.toString(), auth.orEmpty(), obfs.orEmpty(), up.toString(), down.toString(), tls.identity()),
                name = parts.name,
                host = parts.host,
                port = parts.port,
                auth = auth,
                obfs = obfs,
                upMbps = up,
                downMbps = down,
                tls = tls,
            ),
        )
    }

    private val HY_OPTIONS = setOf(
        "sni", "peer", "alpn", "fp", "insecure", "allowinsecure", "allow_insecure",
        "skip-cert-verify", "auth", "auth_str", "obfs", "upmbps", "downmbps", "protocol",
    )
}

object TuicUriParser {
    fun parse(raw: String): ParseResult<TuicProfile> {
        val parts = parseUri(raw, setOf("tuic"), "TUIC") ?: return uriError("TUIC")
        val query = parts.query
        unsupported(query, TUIC_OPTIONS)?.let { return it }
        if (query.requestsInsecureTls()) return insecureTlsError()
        val credentials = parts.uri.rawUserInfo.orEmpty().split(':', limit = 2)
        val uuid = credentials.firstOrNull()?.let(::decode)?.let(::canonicalUuid)
            ?: return ParseResult.Error("invalid_uuid", "شناسهٔ UUID در کانفیگ TUIC معتبر نیست")
        val password = credentials.getOrNull(1)?.let(::decode)?.takeIf(String::isNotEmpty)
        if (password?.length?.let { it > MAX_SECRET_CHARS } == true) {
            return ParseResult.Error("invalid_password", "رمز TUIC بیش از حد طولانی است")
        }
        val congestion = (query["congestion_control"] ?: query["congestion-control"])
            .orEmpty().ifBlank { "cubic" }.lowercase()
        if (congestion !in setOf("cubic", "new_reno", "bbr")) {
            return ParseResult.Error("unsupported_congestion", "الگوریتم congestion control در TUIC پشتیبانی نمی‌شود")
        }
        val relay = (query["udp_relay_mode"] ?: query["udp-relay-mode"])
            .orEmpty().ifBlank { "native" }.lowercase()
        if (relay !in setOf("native", "quic")) {
            return ParseResult.Error("unsupported_udp_relay", "حالت UDP relay در TUIC پشتیبانی نمی‌شود")
        }
        val tls = strictTls(query, parts.host)
        return ParseResult.Success(
            TuicProfile(
                id = stableId("tuic", parts.host.lowercase(), parts.port.toString(), uuid, password.orEmpty(), congestion, relay, tls.identity()),
                name = parts.name,
                host = parts.host,
                port = parts.port,
                uuid = uuid,
                password = password,
                congestionControl = congestion,
                udpRelayMode = relay,
                tls = tls,
            ),
        )
    }

    private val TUIC_OPTIONS = setOf(
        "sni", "peer", "alpn", "fp", "insecure", "allowinsecure", "allow_insecure",
        "skip-cert-verify", "congestion_control", "congestion-control", "udp_relay_mode", "udp-relay-mode",
    )
}

object AnyTlsUriParser {
    fun parse(raw: String): ParseResult<AnyTlsProfile> {
        val parts = parseUri(raw, setOf("anytls"), "anyTLS") ?: return uriError("anyTLS")
        val query = parts.query
        unsupported(query, ANYTLS_OPTIONS)?.let { return it }
        if (query.requestsInsecureTls()) return insecureTlsError()
        val password = decode(parts.uri.rawUserInfo.orEmpty())
        if (password.isBlank() || password.length > MAX_SECRET_CHARS) {
            return ParseResult.Error("invalid_password", "رمز anyTLS وجود ندارد یا بیش از حد طولانی است")
        }
        val tls = strictTls(query, parts.host)
        return ParseResult.Success(
            AnyTlsProfile(
                id = stableId("anytls", parts.host.lowercase(), parts.port.toString(), password, tls.identity()),
                name = parts.name,
                host = parts.host,
                port = parts.port,
                password = password,
                tls = tls,
            ),
        )
    }

    private val ANYTLS_OPTIONS = setOf(
        "sni", "peer", "alpn", "fp", "insecure", "allowinsecure", "allow_insecure", "skip-cert-verify",
    )
}

private data class UriParts(
    val uri: URI,
    val host: String,
    val port: Int,
    val name: String,
    val query: Map<String, String>,
)

private fun parseUri(raw: String, schemes: Set<String>, label: String): UriParts? = runCatching {
    val uri = URI(raw.trim())
    require(uri.scheme?.lowercase() in schemes)
    require(uri.rawPath.isNullOrEmpty())
    val host = requireNotNull(uri.host).removeSurrounding("[", "]").also {
        require(it.isNotBlank() && it.length <= 253 && it.none(Char::isWhitespace))
    }
    require(uri.port in 1..65535)
    val query = parseQuery(uri.rawQuery)
    val name = decode(uri.rawFragment.orEmpty()).ifBlank { "$host:${uri.port}" }.take(120)
    UriParts(uri, host, uri.port, name, query)
}.getOrNull()

private fun parseQuery(raw: String?): Map<String, String> {
    val result = linkedMapOf<String, String>()
    raw.orEmpty().split('&').filter(String::isNotBlank).forEach { part ->
        val pieces = part.split('=', limit = 2)
        val key = decode(pieces[0]).lowercase()
        require(key.isNotBlank() && key !in result) { "Duplicate or blank query option" }
        result[key] = decode(pieces.getOrElse(1) { "" })
    }
    return result
}

private fun unsupported(query: Map<String, String>, supported: Set<String>): ParseResult.Error? =
    query.keys.firstOrNull { it !in supported }?.let {
        ParseResult.Error("unsupported_option", "گزینهٔ پشتیبانی‌نشده در لینک وجود دارد", it)
    }

private fun strictTls(query: Map<String, String>, host: String): TlsOptions = TlsOptions(
    enabled = true,
    serverName = (query["sni"] ?: query["peer"])?.takeIf(String::isNotBlank) ?: host,
    fingerprint = query["fp"]?.takeIf(String::isNotBlank),
    alpn = query["alpn"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
)

private fun Map<String, String>.requestsInsecureTls(): Boolean =
    this["insecure"].isTrue() || this["allowinsecure"].isTrue() || this["allow_insecure"].isTrue() ||
        this["skip-cert-verify"].isTrue()

private fun String?.isTrue(): Boolean = this?.trim()?.lowercase() in setOf("1", "true", "yes")

private fun positiveMbps(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 1..1_000_000 }

private fun invalidBandwidth() = ParseResult.Error(
    "invalid_bandwidth",
    "مقادیر upmbps و downmbps باید عدد مثبت و معتبر باشند",
)

private fun insecureTlsError() = ParseResult.Error(
    "insecure_tls_forbidden",
    "نادیده‌گرفتن اعتبار TLS مجاز نیست",
)

private fun uriError(label: String) = ParseResult.Error(
    "invalid_uri",
    "ساختار لینک $label یا host/port آن معتبر نیست",
)

private fun canonicalUuid(value: String): String? = runCatching { UUID.fromString(value.trim()).toString() }.getOrNull()

private fun decode(value: String): String = runCatching {
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private fun stableId(vararg fields: String): String = MessageDigest.getInstance("SHA-256")
    .digest(fields.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
    .take(24)

private fun TlsOptions.identity(): String = listOf(
    enabled.toString(), realityEnabled.toString(), serverName.orEmpty().lowercase(),
    fingerprint.orEmpty().lowercase(), realityPublicKey.orEmpty(), realityShortId.orEmpty(),
    alpn.joinToString(",") { it.lowercase() },
).joinToString("|")

private fun TransportOptions.identity(): String = listOf(
    type.wireName, path.orEmpty(), host.orEmpty().lowercase(), serviceName.orEmpty(), authority.orEmpty().lowercase(),
).joinToString("|")

private const val MAX_SECRET_CHARS = 1_024
