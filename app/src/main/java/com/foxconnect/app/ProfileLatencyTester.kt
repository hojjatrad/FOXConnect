package com.foxconnect.app

import com.foxconnect.core.model.AnyTlsProfile
import com.foxconnect.core.model.ConnectableProfile
import com.foxconnect.core.model.HttpProxyProfile
import com.foxconnect.core.model.ShadowsocksProfile
import com.foxconnect.core.model.Socks5Profile
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.TrojanProfile
import com.foxconnect.core.model.VlessProfile
import com.foxconnect.core.model.VmessProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bounded parallel TCP endpoint reachability measurement.
 *
 * This is deliberately labelled endpoint latency in the UI: it verifies a real
 * TCP handshake to the configured server, not credentials or tunneled Internet
 * access. UDP/QUIC-only profiles are reported as unsupported rather than being
 * assigned fabricated timings.
 */
internal object ProfileLatencyTester {
    sealed interface Outcome {
        data class Reachable(val latencyMs: Long) : Outcome
        data object Unreachable : Outcome
        data object Unsupported : Outcome
    }

    suspend fun testAll(profiles: List<ConnectableProfile>): Map<String, Outcome> = coroutineScope {
        val gate = Semaphore(MAX_PARALLEL_TESTS)
        profiles.map { profile ->
            async(Dispatchers.IO) {
                profile.id to gate.withPermit { test(profile) }
            }
        }.awaitAll().toMap()
    }

    private suspend fun test(profile: ConnectableProfile): Outcome = withContext(Dispatchers.IO) {
        if (!supportsTcpEndpointProbe(profile)) return@withContext Outcome.Unsupported
        runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceIn(0, MAX_RECORDED_MS)
            Outcome.Reachable(elapsed)
        }.getOrDefault(Outcome.Unreachable)
    }

    private fun supportsTcpEndpointProbe(profile: ConnectableProfile): Boolean = when (profile) {
        is VlessProfile -> profile.transport.type != TransportType.MKCP
        is VmessProfile -> profile.transport.type != TransportType.MKCP
        is TrojanProfile -> profile.transport.type != TransportType.MKCP
        is ShadowsocksProfile,
        is AnyTlsProfile,
        is Socks5Profile,
        is HttpProxyProfile -> true
        else -> false
    }

    private const val MAX_PARALLEL_TESTS = 8
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val MAX_RECORDED_MS = 60_000L
}
