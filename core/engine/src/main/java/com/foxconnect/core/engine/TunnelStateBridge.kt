package com.foxconnect.core.engine

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.model.ProtocolType
import com.foxconnect.core.model.TunnelSnapshot
import com.foxconnect.core.model.TunnelStats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A small, private, credential-free bridge between the UI process and the
 * isolated VPN process. The native core is deliberately kept outside the UI
 * process so a native panic cannot close the activity.
 */
internal class TunnelStateStore(context: Context) {
    private val runtimeDirectory = context.applicationContext.filesDir.resolve("runtime").apply { mkdirs() }
    private val file = AtomicFile(runtimeDirectory.resolve("tunnel-state-v1.bin"))
    private val lockFile = runtimeDirectory.resolve("tunnel-state-v1.lock")

    @Synchronized
    fun write(snapshot: TunnelSnapshot, heartbeatElapsedMs: Long = SystemClock.elapsedRealtime()) =
        withProcessLock {
            val payload = encode(snapshot, heartbeatElapsedMs)
            require(payload.size <= MAX_BYTES)
            val output = file.startWrite()
            try {
                output.write(payload)
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }

    @Synchronized
    fun read(): StoredTunnelState? = withProcessLock {
        if (!file.baseFile.isFile || file.baseFile.length() !in 1..MAX_BYTES.toLong()) return@withProcessLock null
        runCatching { decode(file.readFully()) }.getOrNull()
    }

    private inline fun <T> withProcessLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun encode(snapshot: TunnelSnapshot, heartbeatElapsedMs: Long): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeLong(heartbeatElapsedMs.coerceAtLeast(0))
                output.writeLong(System.currentTimeMillis().coerceAtLeast(0))
                val state = snapshot.state
                output.writeUTF(
                    when (state) {
                        ConnectionState.Disconnected -> STATE_DISCONNECTED
                        is ConnectionState.Connecting -> STATE_CONNECTING
                        is ConnectionState.Switching -> STATE_SWITCHING
                        is ConnectionState.Connected -> STATE_CONNECTED
                        is ConnectionState.Failed -> STATE_FAILED
                    },
                )
                output.writeNullableUtf8(snapshot.profileName)
                output.writeNullableUtf8(snapshot.protocol?.name)
                when (state) {
                    is ConnectionState.Connected -> output.writeLong(state.verifiedAtEpochMs)
                    is ConnectionState.Failed -> {
                        output.writeNullableUtf8(state.userMessage)
                        output.writeNullableUtf8(state.technicalMessage)
                    }
                    else -> Unit
                }
                output.writeInt(snapshot.failoverCount.coerceAtLeast(0))
                output.writeNullableLong(snapshot.lastHealthCheckEpochMs)
                output.writeStats(snapshot.stats)
            }
            bytes.toByteArray()
        }

    private fun decode(payload: ByteArray): StoredTunnelState =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION)
            val heartbeat = input.readLong().also { require(it >= 0) }
            val updatedAt = input.readLong().also { require(it >= 0) }
            val stateCode = input.readUTF()
            val profileName = input.readNullableUtf8()
            val protocol = input.readNullableUtf8()?.let { ProtocolType.valueOf(it) }
            val state = when (stateCode) {
                STATE_DISCONNECTED -> ConnectionState.Disconnected
                STATE_CONNECTING -> ConnectionState.Connecting(profileName.orEmpty())
                STATE_SWITCHING -> ConnectionState.Switching(profileName)
                STATE_CONNECTED -> ConnectionState.Connected(profileName.orEmpty(), input.readLong())
                STATE_FAILED -> ConnectionState.Failed(
                    input.readNullableUtf8().orEmpty(),
                    input.readNullableUtf8(),
                )
                else -> error("Unsupported tunnel state")
            }
            val failoverCount = input.readInt().also { require(it >= 0) }
            val lastHealth = input.readNullableLong()
            val stats = input.readStats()
            require(input.read() == -1)
            StoredTunnelState(
                TunnelSnapshot(state, protocol, profileName, stats, failoverCount, lastHealth),
                heartbeat,
                updatedAt,
            )
        }

    private fun DataOutputStream.writeStats(stats: TunnelStats) {
        writeNullableLong(stats.rxBytes)
        writeNullableLong(stats.txBytes)
        writeNullableLong(stats.rxBytesPerSecond)
        writeNullableLong(stats.txBytesPerSecond)
        writeNullableLong(stats.connectedAtEpochMs)
        writeNullableUtf8(stats.exitIp)
        writeNullableUtf8(stats.countryCode)
        writeNullableLong(stats.latencyMs)
    }

    private fun DataInputStream.readStats() = TunnelStats(
        rxBytes = readNullableLong(),
        txBytes = readNullableLong(),
        rxBytesPerSecond = readNullableLong(),
        txBytesPerSecond = readNullableLong(),
        connectedAtEpochMs = readNullableLong(),
        exitIp = readNullableUtf8(),
        countryCode = readNullableUtf8(),
        latencyMs = readNullableLong(),
    )

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        writeBoolean(value != null)
        if (value == null) return
        val bytes = value.take(MAX_STRING_CHARS).toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readNullableUtf8(): String? {
        if (!readBoolean()) return null
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private companion object {
        val PROCESS_LOCK = Any()
        const val VERSION = 1
        const val MAX_BYTES = 32 * 1024
        const val MAX_STRING_BYTES = 8 * 1024
        const val MAX_STRING_CHARS = 2 * 1024
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_SWITCHING = "switching"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"
    }
}

internal data class StoredTunnelState(
    val snapshot: TunnelSnapshot,
    val heartbeatElapsedMs: Long,
    val updatedAtEpochMs: Long,
)

/** Writes runtime changes from the VPN process to the private state bridge. */
internal class TunnelStatePublisher(context: Context) {
    private val store = TunnelStateStore(context)

    fun connecting(profileName: String, protocol: ProtocolType) {
        TunnelRuntime.connecting(profileName, protocol)
        publish()
    }

    fun switching(profileName: String?, protocol: ProtocolType?) {
        TunnelRuntime.switching(profileName, protocol)
        publish()
    }

    fun verified(profileName: String, protocol: ProtocolType, switched: Boolean, latencyMs: Long?) {
        TunnelRuntime.verified(profileName, protocol, switched, latencyMs)
        publish()
    }

    fun healthChecked(result: HealthProbeResult) {
        TunnelRuntime.healthChecked(result)
        publish()
    }

    fun failed(userMessage: String, technicalMessage: String? = null) {
        TunnelRuntime.failed(userMessage, technicalMessage)
        publish()
    }

    fun disconnected() {
        TunnelRuntime.disconnected()
        publish()
    }

    fun publish() {
        runCatching { store.write(TunnelRuntime.snapshot.value) }
    }
}

/** Keeps the UI runtime synchronized and detects a native VPN-process death. */
class TunnelRuntimeObserver(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = TunnelStateStore(appContext)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var lastAppliedUpdate = -1L
            while (isActive) {
                val stored = runCatching { store.read() }.getOrNull()
                if (stored != null) {
                    val elapsedNow = SystemClock.elapsedRealtime()
                    val stale = stored.snapshot.state.isActiveConnection() &&
                        (stored.heartbeatElapsedMs > elapsedNow ||
                            elapsedNow - stored.heartbeatElapsedMs > HEARTBEAT_TIMEOUT_MS)
                    if (stale) {
                        val failed = stored.snapshot.copy(
                            state = ConnectionState.Failed(
                                appContext.getString(R.string.vpn_process_recovering),
                                "vpn_process_recovery_pending",
                            ),
                            stats = TunnelStats.Empty,
                        )
                        TunnelRuntime.replace(failed)
                        // Preserve explicit user authorization: the sticky VPN service may be
                        // restarting under its persisted, rate-limited recovery budget.
                        runCatching { TunnelEventLog(appContext).append(TunnelEventCode.CORE_PROCESS_STOPPED) }
                        runCatching { store.write(failed) }
                        lastAppliedUpdate = System.currentTimeMillis()
                    } else if (stored.updatedAtEpochMs != lastAppliedUpdate) {
                        TunnelRuntime.replace(stored.snapshot)
                        lastAppliedUpdate = stored.updatedAtEpochMs
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun ConnectionState.isActiveConnection(): Boolean =
        this is ConnectionState.Connecting || this is ConnectionState.Switching || this is ConnectionState.Connected

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val HEARTBEAT_TIMEOUT_MS = 7_000L
    }
}
