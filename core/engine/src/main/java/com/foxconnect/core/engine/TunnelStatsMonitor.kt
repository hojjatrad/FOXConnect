package com.foxconnect.core.engine

import android.net.TrafficStats
import android.os.Process
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal class TunnelStatsMonitor(
    private val nativeTraffic: () -> NativeTrafficSnapshot?,
) {
    suspend fun run() {
        val uidBaselineRx = uidRxBytes()
        val uidBaselineTx = uidTxBytes()
        var uidPreviousRx = uidBaselineRx
        var uidPreviousTx = uidBaselineTx
        var nativeBaseline: NativeTrafficSnapshot? = null
        var reportedRx = 0L
        var reportedTx = 0L

        // Identity failure is non-fatal: the UI keeps honest em dashes. This
        // ordinary, unprotected request is also forced through the active TUN.
        TunnelIdentityProbe().query()?.let { identity ->
            TunnelRuntime.updateStats {
                it.copy(exitIp = identity.ip, countryCode = identity.countryCode)
            }
        }

        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val native = nativeTraffic()
            if (native != null) {
                if (nativeBaseline == null) nativeBaseline = native
                val baseline = checkNotNull(nativeBaseline)
                reportedRx = (native.rxBytes - baseline.rxBytes).coerceAtLeast(0L)
                reportedTx = (native.txBytes - baseline.txBytes).coerceAtLeast(0L)
                TunnelRuntime.updateStats {
                    it.copy(
                        rxBytes = reportedRx,
                        txBytes = reportedTx,
                        rxBytesPerSecond = native.rxBytesPerSecond,
                        txBytesPerSecond = native.txBytesPerSecond,
                    )
                }
                continue
            }

            // Some libbox builds may not expose traffic status. UID accounting
            // remains a clearly bounded fallback; it is never preferred over
            // native counters because forwarded VPN bytes vary across OEMs.
            if (nativeBaseline != null) {
                TunnelRuntime.updateStats {
                    it.copy(rxBytesPerSecond = null, txBytesPerSecond = null)
                }
                continue
            }
            val currentRx = uidRxBytes()
            val currentTx = uidTxBytes()
            if (uidBaselineRx == null || uidBaselineTx == null || currentRx == null || currentTx == null) continue
            reportedRx = (currentRx - uidBaselineRx).coerceAtLeast(0L)
            reportedTx = (currentTx - uidBaselineTx).coerceAtLeast(0L)
            val rxSpeed = uidPreviousRx?.let { (currentRx - it).coerceAtLeast(0L) }
            val txSpeed = uidPreviousTx?.let { (currentTx - it).coerceAtLeast(0L) }
            uidPreviousRx = currentRx
            uidPreviousTx = currentTx
            TunnelRuntime.updateStats {
                it.copy(
                    rxBytes = reportedRx,
                    txBytes = reportedTx,
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
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "close")
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
