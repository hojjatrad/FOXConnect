package com.foxconnect.core.engine

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class TunnelStatsMonitor {
    suspend fun run() {
        val baselineRx = uidRxBytes()
        val baselineTx = uidTxBytes()
        var previousRx = baselineRx
        var previousTx = baselineTx

        // Identity failure is non-fatal: the UI keeps honest em dashes.
        TunnelIdentityProbe().query()?.let { identity ->
            TunnelRuntime.updateStats {
                it.copy(exitIp = identity.ip, countryCode = identity.countryCode)
            }
        }

        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val currentRx = uidRxBytes()
            val currentTx = uidTxBytes()
            if (baselineRx == null || baselineTx == null || currentRx == null || currentTx == null) continue
            val rxTotal = (currentRx - baselineRx).coerceAtLeast(0L)
            val txTotal = (currentTx - baselineTx).coerceAtLeast(0L)
            val rxSpeed = previousRx?.let { (currentRx - it).coerceAtLeast(0L) }
            val txSpeed = previousTx?.let { (currentTx - it).coerceAtLeast(0L) }
            previousRx = currentRx
            previousTx = currentTx
            TunnelRuntime.updateStats {
                it.copy(
                    rxBytes = rxTotal,
                    txBytes = txTotal,
                    rxBytesPerSecond = rxSpeed,
                    txBytesPerSecond = txSpeed,
                )
            }
        }
    }

    private fun uidRxBytes(): Long? = TrafficStats.getUidRxBytes(Process.myUid())
        .takeUnless { it == TrafficStats.UNSUPPORTED.toLong() || it < 0L }

    private fun uidTxBytes(): Long? = TrafficStats.getUidTxBytes(Process.myUid())
        .takeUnless { it == TrafficStats.UNSUPPORTED.toLong() || it < 0L }
}

private data class TunnelIdentity(val ip: String, val countryCode: String?)

private class TunnelIdentityProbe {
    suspend fun query(): TunnelIdentity? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(TRACE_URL).openConnection() as HttpsURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val values = connection.inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val separator = line.indexOf('=')
                        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                    }.toMap()
                }
                val ip = values["ip"]?.takeIf(String::isNotBlank) ?: return@runCatching null
                TunnelIdentity(ip = ip, countryCode = values["loc"]?.takeIf { it.length == 2 })
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    }
}
