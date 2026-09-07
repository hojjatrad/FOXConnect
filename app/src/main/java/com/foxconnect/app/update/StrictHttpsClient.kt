package com.foxconnect.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class StrictHttpsClient {
    suspend fun getMetadata(url: String, maxBytes: Long): HttpBytes = withContext(Dispatchers.IO) {
        requestBytes(url, maxBytes, RequestKind.METADATA)
    }

    suspend fun getAssetText(url: String, maxBytes: Long): HttpBytes = withContext(Dispatchers.IO) {
        requestBytes(url, maxBytes, RequestKind.ASSET)
    }

    suspend fun downloadAsset(
        url: String,
        destination: File,
        maxBytes: Long,
        expectedBytes: Long,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        require(maxBytes in 1..UpdateReleaseParser.MAX_APK_BYTES)
        require(expectedBytes in 1..maxBytes)
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()
        destination.delete()

        var current = checkedUrl(url, RequestKind.ASSET)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = open(current)
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw HttpProblem.InvalidRedirect
                    val location = connection.getHeaderField("Location") ?: throw HttpProblem.InvalidRedirect
                    current = checkedUrl(URL(current, location).toString(), RequestKind.ASSET)
                    return@repeat
                }
                if (code != HttpsURLConnection.HTTP_OK) throw HttpProblem.Status(code)
                val declared = connection.contentLengthLong
                if (declared > maxBytes || declared > expectedBytes) throw HttpProblem.TooLarge
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(partial, false).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes || total > expectedBytes) throw HttpProblem.TooLarge
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                    if (total != expectedBytes) throw HttpProblem.Truncated
                    if (!partial.renameTo(destination)) throw HttpProblem.Storage
                    return@withContext DownloadedFile(
                        file = destination,
                        bytes = total,
                        sha256 = digest.digest().toHex(),
                    )
                } catch (error: Throwable) {
                    partial.delete()
                    destination.delete()
                    throw error
                }
            } finally {
                connection.disconnect()
            }
        }
        throw HttpProblem.InvalidRedirect
    }

    private fun requestBytes(rawUrl: String, maxBytes: Long, kind: RequestKind): HttpBytes {
        require(maxBytes > 0)
        var current = checkedUrl(rawUrl, kind)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = open(current)
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw HttpProblem.InvalidRedirect
                    val location = connection.getHeaderField("Location") ?: throw HttpProblem.InvalidRedirect
                    current = checkedUrl(URL(current, location).toString(), kind)
                    return@repeat
                }
                if (code != HttpsURLConnection.HTTP_OK) throw HttpProblem.Status(code)
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw HttpProblem.TooLarge
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024L).toInt())
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw HttpProblem.TooLarge
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                return HttpBytes(code, body)
            } finally {
                connection.disconnect()
            }
        }
        throw HttpProblem.InvalidRedirect
    }

    private fun open(url: URL): HttpsURLConnection =
        (url.openConnection() as? HttpsURLConnection ?: throw HttpProblem.InsecureUrl).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream;q=0.9")
            setRequestProperty("User-Agent", "FOXConnect-Android-Updater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            useCaches = false
        }

    private fun checkedUrl(raw: String, kind: RequestKind): URL {
        val uri = runCatching { URI(raw) }.getOrElse { throw HttpProblem.InsecureUrl }
        if (
            uri.scheme != "https" || uri.userInfo != null || uri.fragment != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            throw HttpProblem.InsecureUrl
        }
        val host = uri.host?.lowercase() ?: throw HttpProblem.InsecureUrl
        val allowed = when (kind) {
            RequestKind.METADATA -> host == "api.github.com"
            RequestKind.ASSET -> host == "github.com" ||
                host == "release-assets.githubusercontent.com" ||
                host == "objects.githubusercontent.com"
        }
        if (!allowed) throw HttpProblem.InsecureUrl
        return runCatching { uri.toURL() }.getOrElse { throw HttpProblem.InsecureUrl }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private enum class RequestKind { METADATA, ASSET }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal data class HttpBytes(val status: Int, val body: ByteArray)
internal data class DownloadedFile(val file: File, val bytes: Long, val sha256: String)

internal sealed class HttpProblem : Exception() {
    data class Status(val code: Int) : HttpProblem()
    data object TooLarge : HttpProblem()
    data object InsecureUrl : HttpProblem()
    data object InvalidRedirect : HttpProblem()
    data object Truncated : HttpProblem()
    data object Storage : HttpProblem()
}
