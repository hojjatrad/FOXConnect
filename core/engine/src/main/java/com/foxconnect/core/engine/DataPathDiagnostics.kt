package com.foxconnect.core.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class DataPathSnapshot(
    val tunEstablished: Boolean,
    val physicalNetworkAvailable: Boolean,
    val protectedSocketCount: Long,
    val bootstrapDnsRequestCount: Long,
    val bootstrapDnsSuccessCount: Long,
) {
    val readyForVerifiedTraffic: Boolean
        get() = tunEstablished && physicalNetworkAvailable && protectedSocketCount > 0
}

/** In-memory categorical counters only; no host, address, profile, or credential is retained. */
internal object DataPathReadinessVerifier {
    fun failureReason(snapshot: DataPathSnapshot): String? = when {
        !snapshot.tunEstablished -> "tun_not_observed"
        !snapshot.physicalNetworkAvailable -> "physical_network_not_observed"
        snapshot.protectedSocketCount <= 0 -> "socket_protection_not_observed"
        else -> null
    }

    fun nativeTrafficObserved(snapshot: NativeTrafficSnapshot?): Boolean =
        snapshot != null && snapshot.rxBytes > 0 && snapshot.txBytes > 0
}

internal class DataPathDiagnostics {
    private val tunEstablished = AtomicBoolean(false)
    private val physicalNetworkAvailable = AtomicBoolean(false)
    private val protectedSocketCount = AtomicLong(0)
    private val bootstrapDnsRequestCount = AtomicLong(0)
    private val bootstrapDnsSuccessCount = AtomicLong(0)

    fun recordTunEstablished() {
        tunEstablished.set(true)
    }

    fun recordPhysicalNetwork(available: Boolean) {
        physicalNetworkAvailable.set(available)
    }

    fun recordProtectedSocket() {
        protectedSocketCount.incrementAndGet()
    }

    fun recordBootstrapDnsRequest() {
        bootstrapDnsRequestCount.incrementAndGet()
    }

    fun recordBootstrapDnsSuccess() {
        bootstrapDnsSuccessCount.incrementAndGet()
    }

    fun snapshot() = DataPathSnapshot(
        tunEstablished = tunEstablished.get(),
        physicalNetworkAvailable = physicalNetworkAvailable.get(),
        protectedSocketCount = protectedSocketCount.get(),
        bootstrapDnsRequestCount = bootstrapDnsRequestCount.get(),
        bootstrapDnsSuccessCount = bootstrapDnsSuccessCount.get(),
    )
}
