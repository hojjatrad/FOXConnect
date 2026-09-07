package com.foxconnect.core.engine

import android.content.Context
import android.util.AtomicFile

/**
 * A credential-free, app-private marker proving that the current service start
 * came from an explicit user action or the user's enabled boot policy.
 */
internal class TunnelRunAuthorization(context: Context) {
    private val file = AtomicFile(
        context.applicationContext.filesDir.resolve("engine/run-authorized-v1").also {
            it.parentFile?.mkdirs()
        },
    )

    @Synchronized
    fun authorize() {
        writeState(STATE_REQUESTED)
    }

    @Synchronized
    fun markVerified() {
        if (readState() != null) writeState(STATE_VERIFIED)
    }

    @Synchronized
    fun revoke() {
        file.delete()
    }

    @Synchronized
    fun isAuthorized(): Boolean = readState() != null

    @Synchronized
    fun isVerified(): Boolean = readState() == STATE_VERIFIED

    private fun writeState(state: Int) {
        val output = file.startWrite()
        try {
            output.write(state)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun readState(): Int? {
        if (!file.baseFile.isFile || file.baseFile.length() != 1L) return null
        return runCatching { file.readFully().single().toInt() and 0xff }
            .getOrNull()
            ?.takeIf { it == STATE_REQUESTED || it == STATE_VERIFIED }
    }

    private companion object {
        const val STATE_REQUESTED = 1
        const val STATE_VERIFIED = 2
    }
}

/**
 * The VPN runs in its own process, so a UI-process crash must not tear down an
 * already verified tunnel. Record only a credential-free event. Explicit user
 * disconnect, VPN permission revocation, and Android Force Stop still stop it.
 */
object TunnelEmergencyStop {
    fun request(context: Context) {
        val appContext = context.applicationContext
        runCatching { TunnelEventLog(appContext).append(TunnelEventCode.UI_PROCESS_CRASHED) }
    }
}
