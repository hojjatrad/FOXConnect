package com.foxconnect.app.importer

import com.foxconnect.core.parser.UniversalConfigImporter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Panels supported through their documented HTTPS APIs. */
enum class PanelProvider {
    MARZBAN,
    PASARGUARD,
    HIDDIFY,
}

/**
 * Ephemeral panel credentials. This object must never be persisted, logged, put in
 * WorkManager input, or included in an exception. The caller must invoke [clear].
 */
class PanelImportRequest(
    val provider: PanelProvider,
    val serverUrl: String,
    val username: String,
    val password: CharArray,
    val hiddifyClientPath: String = "",
) {
    fun clear() = password.fill('\u0000')

    override fun toString(): String = "PanelImportRequest(provider=$provider, credentials=redacted)"
}

enum class PanelImportFailureReason {
    INVALID_INPUT,
    AUTHENTICATION,
    FORBIDDEN,
    NETWORK,
    TLS,
    REDIRECT_BLOCKED,
    RESPONSE_TOO_LARGE,
    TOO_MANY_USERS,
    UNSUPPORTED_RESPONSE,
    NO_SUPPORTED_CONFIGS,
}

sealed interface PanelImportResult {
    /** Payload bytes are kept in memory only and must be wiped by the caller after parsing. */
    class Success(
        val payloads: List<ByteArray>,
        val skippedSources: Int = 0,
    ) : PanelImportResult {
        override fun toString(): String =
            "PanelImportResult.Success(payloads=${payloads.size}, skipped=$skippedSources, content=redacted)"
    }

    data class Failure(val reason: PanelImportFailureReason) : PanelImportResult
}

/**
 * Read-only, bounded panel importer. Admin credentials are used only on the
 * exact origin entered by the user. Bearer and Basic credentials are never
 * forwarded to subscription URLs or across redirects.
 */
class PanelImportClient internal constructor(
    private val transport: PanelHttpTransport,
) {
    constructor() : this(StrictPanelHttpTransport())

    suspend fun fetch(request: PanelImportRequest): PanelImportResult = try {
        fetchValidated(request)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // Never surface exception messages from HTTP/JSON libraries: they can
        // include a private URL, response fragment, token, or configuration.
        PanelImportResult.Failure(PanelImportFailureReason.NETWORK)
    }

    private suspend fun fetchValidated(request: PanelImportRequest): PanelImportResult {
        val base = PanelUrls.panelBase(request.serverUrl)
            ?: return PanelImportResult.Failure(PanelImportFailureReason.INVALID_INPUT)
        if (
            request.username.isBlank() ||
            request.username.length > MAX_USERNAME_CHARS ||
            request.password.isEmpty() ||
            request.password.size > MAX_PASSWORD_CHARS
        ) {
            return PanelImportResult.Failure(PanelImportFailureReason.INVALID_INPUT)
        }
        return when (request.provider) {
            PanelProvider.MARZBAN,
            PanelProvider.PASARGUARD -> fetchOAuthPanel(base, request)
            PanelProvider.HIDDIFY -> fetchHiddify(base, request)
        }
    }

    private suspend fun fetchOAuthPanel(base: URI, request: PanelImportRequest): PanelImportResult {
        val tokenBody = formBody(request.username, request.password)
        val tokenResponse = try {
            transport.execute(
                PanelHttpRequest(
                    url = PanelUrls.append(base, "api/admin/token"),
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = tokenBody,
                    maxResponseBytes = MAX_TOKEN_BYTES,
                ),
            )
        } finally {
            tokenBody.fill(0)
        }
        val tokenHttp = tokenResponse.responseOrFailure() ?: return tokenResponse.toImportFailure()
        if (tokenHttp.status !in 200..299) {
            tokenHttp.body.fill(0)
            return PanelImportResult.Failure(
                when (tokenHttp.status) {
                    401 -> PanelImportFailureReason.AUTHENTICATION
                    403 -> PanelImportFailureReason.FORBIDDEN
                    else -> PanelImportFailureReason.UNSUPPORTED_RESPONSE
                },
            )
        }
        val token = try {
            PanelApiParser.accessToken(tokenHttp.body)
        } finally {
            tokenHttp.body.fill(0)
        } ?: return PanelImportResult.Failure(PanelImportFailureReason.UNSUPPORTED_RESPONSE)

        val usersUri = PanelUrls.append(base, "api/users?offset=0&limit=$MAX_PANEL_USERS&load_sub=true")
        val usersResponse = transport.execute(
            PanelHttpRequest(
                url = usersUri,
                headers = mapOf("Authorization" to "Bearer $token"),
                maxResponseBytes = MAX_USERS_RESPONSE_BYTES,
            ),
        )
        val usersHttp = usersResponse.responseOrFailure() ?: return usersResponse.toImportFailure()
        if (usersHttp.status !in 200..299) {
            usersHttp.body.fill(0)
            return PanelImportResult.Failure(
                when (usersHttp.status) {
                    401 -> PanelImportFailureReason.AUTHENTICATION
                    403 -> PanelImportFailureReason.FORBIDDEN
                    else -> PanelImportFailureReason.UNSUPPORTED_RESPONSE
                },
            )
        }
        val page = try {
            PanelApiParser.oauthUsers(usersHttp.body)
        } finally {
            usersHttp.body.fill(0)
        } ?: return PanelImportResult.Failure(PanelImportFailureReason.UNSUPPORTED_RESPONSE)
        if ((page.total ?: page.userCount) > MAX_PANEL_USERS || page.userCount > MAX_PANEL_USERS) {
            return PanelImportResult.Failure(PanelImportFailureReason.TOO_MANY_USERS)
        }
        if (page.invalidSubscriptionUrl) {
            return PanelImportResult.Failure(PanelImportFailureReason.UNSUPPORTED_RESPONSE)
        }

        val payloads = mutableListOf<ByteArray>()
        var totalPayloadBytes = 0
        var skippedSources = 0
        try {
            if (page.links.isNotEmpty()) {
                val direct = page.links.take(MAX_CONFIGS).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
                if (direct.size > MAX_TOTAL_PAYLOAD_BYTES) {
                    direct.fill(0)
                    return PanelImportResult.Failure(PanelImportFailureReason.RESPONSE_TOO_LARGE)
                }
                payloads += direct
                totalPayloadBytes += direct.size
            }
            // Marzban already returns generated plain links in the bounded user-list
            // response. PasarGuard returns subscription URLs instead, so only that
            // link-less response needs per-user subscription retrieval.
            val subscriptionUris = if (page.links.isEmpty()) page.subscriptionUrls else emptyList()
            for (subscriptionUri in subscriptionUris.distinct().take(MAX_PANEL_USERS)) {
                currentCoroutineContext().ensureActive()
                val result = transport.execute(
                    PanelHttpRequest(
                        url = subscriptionUri,
                        maxResponseBytes = MAX_SUBSCRIPTION_BYTES,
                    ),
                )
                val response = result.responseOrFailure() ?: run {
                    payloads.wipe()
                    return result.toImportFailure()
                }
                if (response.status !in 200..299) {
                    response.body.fill(0)
                    skippedSources++
                    continue
                }
                if (totalPayloadBytes + response.body.size > MAX_TOTAL_PAYLOAD_BYTES) {
                    response.body.fill(0)
                    payloads.wipe()
                    return PanelImportResult.Failure(PanelImportFailureReason.RESPONSE_TOO_LARGE)
                }
                payloads += response.body
                totalPayloadBytes += response.body.size
            }
            return validatedPayloads(payloads, skippedSources)
        } catch (error: Throwable) {
            payloads.wipe()
            throw error
        }
    }

    private suspend fun fetchHiddify(base: URI, request: PanelImportRequest): PanelImportResult {
        val authorization = basicAuthorization(request.username, request.password)
        if (request.hiddifyClientPath.isBlank()) {
            val directUri = if (base.path.trimEnd('/').endsWith("/sub")) {
                base
            } else {
                PanelUrls.append(base, "sub")
            }
            val direct = transport.execute(
                PanelHttpRequest(
                    url = directUri,
                    headers = mapOf("Authorization" to authorization),
                    maxResponseBytes = MAX_SUBSCRIPTION_BYTES,
                ),
            )
            return when (direct) {
                is PanelHttpResult.Response -> {
                    val supported = try {
                        direct.value.status in 200..299 && payloadHasSupportedConfig(direct.value.body)
                    } catch (error: Throwable) {
                        direct.value.body.fill(0)
                        throw error
                    }
                    if (supported) {
                        PanelImportResult.Success(listOf(direct.value.body))
                    } else {
                        direct.value.body.fill(0)
                        PanelImportResult.Failure(
                            if (direct.value.status == 401 || direct.value.status == 403) {
                                PanelImportFailureReason.AUTHENTICATION
                            } else {
                                PanelImportFailureReason.NO_SUPPORTED_CONFIGS
                            },
                        )
                    }
                }
                is PanelHttpResult.Failure -> direct.toImportFailure()
            }
        }

        // A supplied client path explicitly selects admin mode. Admin Basic
        // credentials therefore go only to the documented API path and never
        // to a speculative personal-subscription endpoint.
        val clientPath = PanelUrls.safePathSegment(request.hiddifyClientPath)
            ?: return PanelImportResult.Failure(PanelImportFailureReason.INVALID_INPUT)
        val adminBase = PanelUrls.hiddifyAdminBase(base)
        val usersResponse = transport.execute(
            PanelHttpRequest(
                url = PanelUrls.append(adminBase, "api/v2/admin/user/"),
                headers = mapOf("Authorization" to authorization),
                maxResponseBytes = MAX_USERS_RESPONSE_BYTES,
            ),
        )
        val usersHttp = usersResponse.responseOrFailure() ?: return usersResponse.toImportFailure()
        if (usersHttp.status !in 200..299) {
            usersHttp.body.fill(0)
            return PanelImportResult.Failure(
                if (usersHttp.status == 401 || usersHttp.status == 403) {
                    PanelImportFailureReason.AUTHENTICATION
                } else {
                    PanelImportFailureReason.UNSUPPORTED_RESPONSE
                },
            )
        }
        val userIds = try {
            PanelApiParser.hiddifyUserIds(usersHttp.body)
        } finally {
            usersHttp.body.fill(0)
        } ?: return PanelImportResult.Failure(PanelImportFailureReason.UNSUPPORTED_RESPONSE)
        if (userIds.size > MAX_PANEL_USERS) {
            return PanelImportResult.Failure(PanelImportFailureReason.TOO_MANY_USERS)
        }

        val payloads = mutableListOf<ByteArray>()
        var totalPayloadBytes = 0
        var skippedSources = 0
        try {
            for (userId in userIds) {
                currentCoroutineContext().ensureActive()
                val subscriptionUri = PanelUrls.originPath(base, "$clientPath/$userId/sub")
                val result = transport.execute(
                    PanelHttpRequest(
                        // Deliberately no admin Authorization header: Hiddify's UUID
                        // subscription route is a separate, least-privilege request.
                        url = subscriptionUri,
                        maxResponseBytes = MAX_SUBSCRIPTION_BYTES,
                    ),
                )
                val response = result.responseOrFailure() ?: run {
                    payloads.wipe()
                    return result.toImportFailure()
                }
                val supported = try {
                    response.status in 200..299 && payloadHasSupportedConfig(response.body)
                } catch (error: Throwable) {
                    response.body.fill(0)
                    throw error
                }
                if (!supported) {
                    response.body.fill(0)
                    skippedSources++
                    continue
                }
                if (totalPayloadBytes + response.body.size > MAX_TOTAL_PAYLOAD_BYTES) {
                    response.body.fill(0)
                    payloads.wipe()
                    return PanelImportResult.Failure(PanelImportFailureReason.RESPONSE_TOO_LARGE)
                }
                payloads += response.body
                totalPayloadBytes += response.body.size
            }
            return validatedPayloads(payloads, skippedSources)
        } catch (error: Throwable) {
            payloads.wipe()
            throw error
        }
    }

    private fun validatedPayloads(
        payloads: MutableList<ByteArray>,
        skippedSources: Int,
    ): PanelImportResult {
        if (payloads.isEmpty() || payloads.none(::payloadHasSupportedConfig)) {
            payloads.wipe()
            return PanelImportResult.Failure(PanelImportFailureReason.NO_SUPPORTED_CONFIGS)
        }
        return PanelImportResult.Success(payloads, skippedSources)
    }

    private fun payloadHasSupportedConfig(payload: ByteArray): Boolean =
        UniversalConfigImporter.importBytes(payload).configs.isNotEmpty()

    private fun formBody(username: String, password: CharArray): ByteArray {
        val passwordString = String(password)
        return try {
            listOf(
                "grant_type" to "password",
                "username" to username,
                "password" to passwordString,
                "scope" to "",
                "client_id" to "",
                "client_secret" to "",
            ).joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }.toByteArray(StandardCharsets.UTF_8)
        } finally {
            // Strings are immutable on Android; the owning CharArray is still
            // wiped by the activity immediately after this one-shot operation.
        }
    }

    private fun basicAuthorization(username: String, password: CharArray): String {
        val clear = "$username:${String(password)}".toByteArray(StandardCharsets.UTF_8)
        return try {
            "Basic ${Base64.getEncoder().encodeToString(clear)}"
        } finally {
            clear.fill(0)
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun MutableList<ByteArray>.wipe() {
        forEach { it.fill(0) }
        clear()
    }

    private companion object {
        const val MAX_USERNAME_CHARS = 256
        const val MAX_PASSWORD_CHARS = 1_024
        const val MAX_PANEL_USERS = 100
        const val MAX_CONFIGS = 512
        const val MAX_TOKEN_BYTES = 64 * 1024
        const val MAX_USERS_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}

internal object PanelUrls {
    fun panelBase(raw: String): URI? = strictHttps(raw, allowQuery = false)?.let { uri ->
        if (uri.path.length > 512) null else URI(
            uri.scheme.lowercase(),
            null,
            uri.host,
            uri.port,
            uri.path.ifBlank { "/" }.trimEnd('/').ifBlank { "/" },
            null,
            null,
        )
    }

    fun append(base: URI, relative: String): URI {
        val path = base.path.trimEnd('/') + "/" + relative.substringBefore('?').trimStart('/')
        val query = relative.substringAfter('?', missingDelimiterValue = "").ifBlank { null }
        return URI("https", null, base.host, base.port, path, query, null)
    }

    fun originPath(base: URI, relative: String): URI =
        URI("https", null, base.host, base.port, "/${relative.trimStart('/')}", null, null)

    fun hiddifyAdminBase(base: URI): URI {
        val suffix = "/api/v2/admin"
        val path = base.path.trimEnd('/').let { if (it.endsWith(suffix)) it.removeSuffix(suffix) else it }
        return URI("https", null, base.host, base.port, path.ifBlank { "/" }, null, null)
    }

    fun safePathSegment(raw: String): String? = raw.trim().trim('/').takeIf {
        it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._~-]+")) && it != "." && it != ".."
    }

    fun subscription(raw: String): URI? = strictHttps(raw, allowQuery = true)

    private fun strictHttps(raw: String, allowQuery: Boolean): URI? = runCatching {
        require(raw.length in 9..2_048)
        require(raw.none { it.isWhitespace() || it.isISOControl() })
        val parsed = URI(raw)
        require(parsed.path.orEmpty().split('/').none { it == "." || it == ".." })
        val uri = parsed.normalize()
        require(uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawFragment == null)
        require(allowQuery || uri.rawQuery == null)
        require(uri.port == -1 || uri.port in 1..65_535)
        require(uri.isAbsolute)
        uri
    }.getOrNull()
}

internal class PanelUserPage(
    val userCount: Int,
    val total: Int?,
    val links: List<String>,
    val subscriptionUrls: List<URI>,
    val invalidSubscriptionUrl: Boolean,
)

internal object PanelApiParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun accessToken(bytes: ByteArray): String? = parse(bytes)?.let { root ->
        (root as? JsonObject)?.get("access_token")?.asString()?.takeIf {
            it.length in 16..16_384 && it.none(Char::isWhitespace)
        }
    }

    fun oauthUsers(bytes: ByteArray): PanelUserPage? {
        val root = parse(bytes) ?: return null
        val users = when (root) {
            is JsonObject -> root["users"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return null
        val links = mutableListOf<String>()
        val subscriptions = mutableListOf<URI>()
        var invalidUrl = false
        for (entry in users) {
            val user = entry as? JsonObject ?: return null
            (user["links"] as? JsonArray)?.forEach { link ->
                link.asString()?.takeIf { it.length <= MAX_LINK_CHARS }?.let(links::add)
            }
            val rawUrls = buildList {
                user["subscription_url"]?.asString()?.takeIf(String::isNotBlank)?.let(::add)
                (user["subscription_urls"] as? JsonArray)?.forEach { item ->
                    item.asString()?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            rawUrls.forEach { raw ->
                val parsed = PanelUrls.subscription(raw)
                if (parsed == null) invalidUrl = true else subscriptions += parsed
            }
        }
        val total = (root as? JsonObject)?.let {
            it["total"]?.asInt() ?: it["count"]?.asInt()
        }
        return PanelUserPage(users.size, total, links.distinct().take(MAX_LINKS), subscriptions, invalidUrl)
    }

    fun hiddifyUserIds(bytes: ByteArray): List<String>? {
        val root = parse(bytes) as? JsonArray ?: return null
        return root.map { item ->
            val raw = (item as? JsonObject)?.get("uuid")?.asString() ?: return null
            runCatching { UUID.fromString(raw).toString() }.getOrNull() ?: return null
        }.distinct()
    }

    private fun parse(bytes: ByteArray): JsonElement? = runCatching {
        require(bytes.size <= MAX_JSON_BYTES)
        json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))
    }.getOrNull()

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement.asInt(): Int? =
        (this as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_LINK_CHARS = 16 * 1024
    private const val MAX_LINKS = 512
}

internal class PanelHttpRequest(
    val url: URI,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val maxResponseBytes: Int,
) {
    override fun toString(): String = "PanelHttpRequest(method=$method, url=redacted, headers=redacted)"
}

internal class PanelHttpResponse(val status: Int, val body: ByteArray) {
    override fun toString(): String = "PanelHttpResponse(status=$status, body=redacted)"
}

internal enum class PanelTransportFailure {
    NETWORK,
    TLS,
    REDIRECT,
    TOO_LARGE,
}

internal sealed interface PanelHttpResult {
    class Response(val value: PanelHttpResponse) : PanelHttpResult
    data class Failure(val reason: PanelTransportFailure) : PanelHttpResult
}

internal interface PanelHttpTransport {
    suspend fun execute(request: PanelHttpRequest): PanelHttpResult
}

internal class StrictPanelHttpTransport : PanelHttpTransport {
    override suspend fun execute(request: PanelHttpRequest): PanelHttpResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val connectionReference = AtomicReference<HttpsURLConnection?>()
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion {
            connectionReference.get()?.disconnect()
        }
        try {
            val connection = (request.url.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = request.method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                doInput = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                request.body?.let { bytes ->
                    doOutput = true
                    setFixedLengthStreamingMode(bytes.size)
                }
            }
            connectionReference.set(connection)
            request.body?.let { bytes -> connection.outputStream.use { it.write(bytes) } }
            val status = connection.responseCode
            if (status in 300..399) return@withContext PanelHttpResult.Failure(PanelTransportFailure.REDIRECT)
            val body = if (status in 200..299) {
                val length = connection.contentLengthLong
                if (length > request.maxResponseBytes) {
                    return@withContext PanelHttpResult.Failure(PanelTransportFailure.TOO_LARGE)
                }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(minOf(length.coerceAtLeast(0).toInt(), 32 * 1024))
                    val buffer = ByteArray(8 * 1024)
                    try {
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > request.maxResponseBytes) {
                                return@withContext PanelHttpResult.Failure(PanelTransportFailure.TOO_LARGE)
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } finally {
                        buffer.fill(0)
                        val used = output.size()
                        output.reset()
                        if (used > 0) output.write(ByteArray(used))
                        output.reset()
                    }
                }
            } else {
                ByteArray(0)
            }
            PanelHttpResult.Response(PanelHttpResponse(status, body))
        } catch (error: SSLException) {
            currentCoroutineContext().ensureActive()
            PanelHttpResult.Failure(PanelTransportFailure.TLS)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            PanelHttpResult.Failure(PanelTransportFailure.NETWORK)
        } catch (error: ClassCastException) {
            PanelHttpResult.Failure(PanelTransportFailure.TLS)
        } finally {
            cancellationHandle.dispose()
            connectionReference.getAndSet(null)?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val USER_AGENT = "FOXConnect-PanelImport/1"
    }
}

private fun PanelHttpResult.responseOrFailure(): PanelHttpResponse? =
    (this as? PanelHttpResult.Response)?.value

private fun PanelHttpResult.toImportFailure(): PanelImportResult.Failure {
    val failure = this as? PanelHttpResult.Failure
    return PanelImportResult.Failure(
        when (failure?.reason) {
            PanelTransportFailure.NETWORK -> PanelImportFailureReason.NETWORK
            PanelTransportFailure.TLS -> PanelImportFailureReason.TLS
            PanelTransportFailure.REDIRECT -> PanelImportFailureReason.REDIRECT_BLOCKED
            PanelTransportFailure.TOO_LARGE -> PanelImportFailureReason.RESPONSE_TOO_LARGE
            null -> PanelImportFailureReason.UNSUPPORTED_RESPONSE
        },
    )
}
