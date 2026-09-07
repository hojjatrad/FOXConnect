package com.foxconnect.core.engine

import android.content.Context
import android.util.AtomicFile
import java.io.RandomAccessFile

enum class TunnelEventCode {
    CONNECT_REQUESTED,
    CONNECTING,
    VERIFIED,
    HEALTH_FAILED,
    TUNNEL_VERIFICATION_FAILED,
    CORE_SETUP_FAILED,
    CORE_VERSION_FAILED,
    CORE_CONFIG_REJECTED,
    CORE_COMMAND_FAILED,
    CORE_NETWORK_MONITOR_FAILED,
    CORE_SERVICE_START_FAILED,
    CORE_POST_START_FAILED,
    TUN_ESTABLISH_FAILED,
    SOCKET_PROTECTION_FAILED,
    CORE_START_FAILED,
    SWITCHING,
    ALL_PROFILES_FAILED,
    KILL_SWITCH_BLOCKING,
    DISCONNECT_REQUESTED,
    DISCONNECTED,
    PERMISSION_REVOKED,
    CORE_PROCESS_STOPPED,
    UI_PROCESS_CRASHED,
}

data class TunnelEvent(
    val timestampEpochMs: Long,
    val code: TunnelEventCode,
)

/** Bounded diagnostic log containing codes only: never endpoints, names, URLs, or credentials. */
class TunnelEventLog(context: Context) {
    private val directory = context.applicationContext.filesDir.resolve("diagnostics").apply { mkdirs() }
    private val file = AtomicFile(directory.resolve("events-v1.log"))
    private val lockFile = directory.resolve("events-v1.lock")

    fun append(code: TunnelEventCode, timestampMs: Long = System.currentTimeMillis()) = withProcessLock {
        val entries = readUnlocked().takeLast(MAX_ENTRIES - 1) +
            TunnelEvent(timestampMs.coerceAtLeast(0), code)
        val payload = entries.joinToString("\n") { "${it.timestampEpochMs}|${it.code.name}" }
            .toByteArray(Charsets.US_ASCII)
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

    fun read(): List<TunnelEvent> = withProcessLock { readUnlocked() }

    private fun readUnlocked(): List<TunnelEvent> {
        if (!file.baseFile.isFile || file.baseFile.length() !in 0..MAX_BYTES.toLong()) return emptyList()
        return runCatching {
            file.readFully().toString(Charsets.US_ASCII).lineSequence()
                .filter(String::isNotBlank)
                .take(MAX_ENTRIES)
                .mapNotNull { line ->
                    val pieces = line.split('|', limit = 2)
                    val timestamp = pieces.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val code = pieces.getOrNull(1)?.let { runCatching { TunnelEventCode.valueOf(it) }.getOrNull() }
                        ?: return@mapNotNull null
                    TunnelEvent(timestamp, code)
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    fun clear() = withProcessLock { file.delete() }

    private inline fun <T> withProcessLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private companion object {
        val PROCESS_LOCK = Any()
        const val MAX_ENTRIES = 200
        const val MAX_BYTES = 32 * 1024
    }
}
