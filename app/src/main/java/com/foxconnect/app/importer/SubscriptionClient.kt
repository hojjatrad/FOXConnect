package com.foxconnect.app.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

sealed interface SubscriptionFetchResult {
    data class Success(val body: ByteArray, val etag: String?) : SubscriptionFetchResult
    data object NotModified : SubscriptionFetchResult
    data class Failure(val errorCode: String) : SubscriptionFetchResult
}

/** Strict-TLS, size-bounded subscription downloader with HTTPS-only redirects. */
class SubscriptionClient {
    suspend fun fetch(rawUrl: String, etag: String? = null): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        try {
            fetchBlocking(validateUrl(rawUrl), etag, redirectsLeft = MAX_REDIRECTS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            SubscriptionFetchResult.Failure("subscription_network_error")
        }
    }

    private fun fetchBlocking(
        url: URL,
        etag: String?,
        redirectsLeft: Int,
    ): SubscriptionFetchResult {
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "text/plain, application/octet-stream, application/json, */*;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "gzip")
        // Keep an honest stable client identity; several panels reject generic
        // Java URLConnection agents or serve an HTML dashboard instead.
        connection.setRequestProperty("User-Agent", "FOXConnect/0.4.1 Android")
        etag?.takeIf { it.length <= 512 }?.let { connection.setRequestProperty("If-None-Match", it) }
        return try {
            when (val code = connection.responseCode) {
                HttpsURLConnection.HTTP_NOT_MODIFIED -> SubscriptionFetchResult.NotModified
                in REDIRECT_CODES -> {
                    if (redirectsLeft <= 0) return SubscriptionFetchResult.Failure("subscription_too_many_redirects")
                    val location = connection.getHeaderField("Location")
                        ?: return SubscriptionFetchResult.Failure("subscription_invalid_redirect")
                    val redirected = validateUrl(URL(url, location).toExternalForm())
                    fetchBlocking(redirected, etag = null, redirectsLeft = redirectsLeft - 1)
                }
                in 200..299 -> {
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > MAX_BODY_BYTES) {
                        SubscriptionFetchResult.Failure("subscription_too_large")
                    } else {
                        val body = connection.inputStream.use { input -> input.readAtMost(MAX_BODY_BYTES + 1) }
                        if (body.size > MAX_BODY_BYTES) {
                            SubscriptionFetchResult.Failure("subscription_too_large")
                        } else {
                            SubscriptionFetchResult.Success(body, connection.getHeaderField("ETag")?.take(512))
                        }
                    }
                }
                else -> SubscriptionFetchResult.Failure("subscription_http_$code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateUrl(value: String): URL {
        require(value.length <= MAX_URL_CHARS) { "Subscription URL is too large" }
        val url = URL(value.trim())
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS subscriptions are allowed" }
        require(url.host.isNotBlank()) { "Subscription host is missing" }
        require(url.userInfo == null) { "Subscription URL user-info is not allowed" }
        require(url.ref == null) { "Subscription URL fragments are not allowed" }
        return url
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_URL_CHARS = 4 * 1024
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
