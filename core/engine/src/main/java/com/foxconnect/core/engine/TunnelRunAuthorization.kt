package com.foxconnect.core.engine

import android.content.Context
import android.content.Intent
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
        val output = file.startWrite()
        try {
            output.write(1)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun revoke() {
        file.delete()
    }

    fun isAuthorized(): Boolean = file.baseFile.isFile && file.baseFile.length() == 1L
}

/** Best-effort fail-closed handling if the UI process itself has an uncaught crash. */
object TunnelEmergencyStop {
    fun request(context: Context) {
        val appContext = context.applicationContext
        runCatching { TunnelRunAuthorization(appContext).revoke() }
        runCatching { EngineConfigStore(appContext).clear() }
        runCatching { TunnelEventLog(appContext).append(TunnelEventCode.UI_PROCESS_CRASHED) }
        runCatching {
            appContext.startService(
                Intent(appContext, FoxVpnService::class.java).setAction(FoxVpnService.ACTION_DISCONNECT),
            )
        }
    }
}
