package com.foxconnect.core.engine

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal data class HealthProbeResult(
    val successful: Boolean,
    val latencyMs: Long? = null,
)

internal object HealthProbeSemantics {
    /** A strict-TLS HTTPS origin proved the path if it returned a syntactically valid HTTP status. */
    fun isValidHttpResponse(statusCode: Int): Boolean = statusCode in 100..599

    fun confirmationIndices(primaryIndex: Int, probeCount: Int): List<Int> {
        require(probeCount > 0)
        require(primaryIndex in 0 until probeCount)
        return (0 until probeCount).filterNot { it == primaryIndex }
    }
}

internal class TunnelHealthVerifier {
    private val nextPrimaryIndex = AtomicInteger(0)

    suspend fun awaitVerified(
        maxAttempts: Int = 2,
        timeoutMs: Int = 8_000,
    ): HealthProbeResult {
        repeat(maxAttempts.coerceIn(1, 3)) { attempt ->
            // Establishment and failover are security boundaries: race all
            // independent providers so one blocked origin cannot cause a false
            // rejection, while still requiring real DNS + TLS + HTTPS via TUN.
            val result = probeAny(PROBES.indices.toList(), timeoutMs)
            if (result.successful) return result
            if (attempt < maxAttempts - 1) delay(RETRY_DELAY_MS)
        }
        return HealthProbeResult(false)
    }

    /**
     * Healthy-state checks use one rotating provider to reduce radio wake-ups
     * and TLS overhead. A primary failure is confirmed against every other
     * provider before the watchdog is told that the tunnel failed.
     *
     * These sockets are deliberately neither protected nor bound to a physical
     * Network. They must follow Android's VPN route; protecting them would make
     * the app report a false Connected state when the tunnel itself is broken.
     */
    suspend fun probeTunnel(timeoutMs: Int): HealthProbeResult {
        val primary = Math.floorMod(nextPrimaryIndex.getAndIncrement(), PROBES.size)
        val first = probeOne(primary, timeoutMs)
        if (first.successful) return first
        return probeAny(
            HealthProbeSemantics.confirmationIndices(primary, PROBES.size),
            timeoutMs,
        )
    }

    private suspend fun probeOne(index: Int, timeoutMs: Int): HealthProbeResult =
        runInterruptible(Dispatchers.IO) {
            probeHttps(PROBES[index], timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))
        }

    private suspend fun probeAny(indices: List<Int>, timeoutMs: Int): HealthProbeResult = coroutineScope {
        if (indices.isEmpty()) return@coroutineScope HealthProbeResult(false)
        val results = Channel<HealthProbeResult>(Channel.UNLIMITED)
        val jobs = indices.map { index ->
            launch {
                results.send(probeOne(index, timeoutMs))
            }
        }
        try {
            repeat(indices.size) {
                val result = results.receive()
                if (result.successful) return@coroutineScope result
            }
            HealthProbeResult(false)
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
    }

    private fun probeHttps(probe: Probe, timeoutMs: Int): HealthProbeResult {
        val started = System.nanoTime()
        val successful = runCatching {
            val connection = URL(probe.url).openConnection() as HttpsURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "close")
            try {
                val responseCode = connection.responseCode
                if (!HealthProbeSemantics.isValidHttpResponse(responseCode)) return@runCatching false
                // Consume one byte when a body exists. TLS-protected response
                // headers are already inbound tunnel traffic; this additionally
                // exercises body delivery without downloading arbitrary content.
                val body = if (responseCode >= 400) connection.errorStream else connection.inputStream
                body?.use { it.read() }
                true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        val latency = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        return HealthProbeResult(successful, latency.takeIf { successful })
    }

    private data class Probe(val url: String)

    private companion object {
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 10_000
        const val RETRY_DELAY_MS = 500L
        val PROBES = listOf(
            Probe("https://connectivitycheck.gstatic.com/generate_204"),
            Probe("https://cp.cloudflare.com/generate_204"),
            Probe("https://captive.apple.com/hotspot-detect.html"),
        )
    }
}
