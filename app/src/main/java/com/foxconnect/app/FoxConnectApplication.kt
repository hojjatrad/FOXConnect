package com.foxconnect.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.foxconnect.app.importer.SubscriptionRefreshScheduler
import com.foxconnect.app.update.UpdateCheckScheduler
import com.foxconnect.core.engine.TunnelEmergencyStop
import com.foxconnect.core.engine.TunnelRuntimeObserver
import com.foxconnect.core.storage.EncryptedProfileStore
import com.foxconnect.core.storage.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FoxConnectApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var profileRepository: ProfileRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // AppCompat locale changes can recreate activities. Never run them from
        // the remote VPN process while the UI process is alive.
        if (!isMainProcess()) return
        installUiCrashStop()

        val preferences = getSharedPreferences("product_preferences", MODE_PRIVATE)
        if (!preferences.contains(KEY_LANGUAGE)) {
            preferences.edit { putString(KEY_LANGUAGE, "fa") }
        }
        val language = preferences.getString(KEY_LANGUAGE, "fa") ?: "fa"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))

        profileRepository = ProfileRepository(EncryptedProfileStore(this), applicationScope)
        TunnelRuntimeObserver(this, applicationScope).start()
        SubscriptionRefreshScheduler.schedule(this)
        UpdateCheckScheduler.sync(this)
    }

    private fun installUiCrashStop() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handling = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handling.compareAndSet(false, true)) {
                // Do not persist the exception or stack: it may contain profile data.
                TunnelEmergencyStop.request(this)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val manager = getSystemService(ActivityManager::class.java)
            manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
                ?: runCatching {
                    java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
                }.getOrNull()
        }
        return processName == packageName
    }

    private companion object { const val KEY_LANGUAGE = "language" }
}
