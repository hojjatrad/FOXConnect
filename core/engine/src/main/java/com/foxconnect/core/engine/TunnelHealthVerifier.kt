package com.foxconnect.core.engine

import java.net.URL
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

internal class TunnelHealthVerifier {
    suspend fun awaitVerified(
        maxAttempts: Int = 2,
        timeoutMs: Int = 3_500,
    ): HealthProbeResult {
        repeat(maxAttempts.coerceIn(1, 3)) { attempt ->
            val result = probeTunnel(timeoutMs)
            if (result.successful) return result
            if (attempt < maxAttempts - 1) delay(500)
        }
        return HealthProbeResult(false)
    }

    /**
     * Exercise DNS, routing and strict TLS through the active VPN. Several
     * independent connectivity-check providers are tried concurrently: one
     * blocked provider must never reject an otherwise working tunnel.
     */
    suspend fun probeTunnel(timeoutMs: Int): HealthProbeResult = coroutineScope {
        val results = Channel<HealthProbeResult>(Channel.UNLIMITED)
        val jobs = PROBES.map { probe ->
            launch {
                val result = runInterruptible(Dispatchers.IO) {
                    probeHttps(probe, timeoutMs.coerceIn(1_000, 10_000))
                }
                results.send(result)
            }
        }
        try {
            repeat(PROBES.size) {
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
            try {
                connection.responseCode in probe.acceptedStatus
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        val latency = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        return HealthProbeResult(successful, latency.takeIf { successful })
    }

    private data class Probe(val url: String, val acceptedStatus: IntRange)

    private companion object {
        val PROBES = listOf(
            Probe("https://connectivitycheck.gstatic.com/generate_204", 204..204),
            Probe("https://cp.cloudflare.com/generate_204", 204..204),
            Probe("https://captive.apple.com/hotspot-detect.html", 200..200),
        )
    }
}
