package com.foxconnect.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxconnect.app.MainActivity
import com.foxconnect.app.R
import java.util.concurrent.TimeUnit

internal object UpdatePreferences {
    private const val FILE = "update_preferences"
    private const val KEY_PERIODIC = "periodic_checks"
    private const val KEY_PRERELEASES = "include_prereleases"
    private const val KEY_LAST_NOTIFIED = "last_notified_version"

    fun periodicChecks(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PERIODIC, false)

    fun includePrereleases(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PRERELEASES, false)

    fun savePolicy(context: Context, periodic: Boolean, prereleases: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PERIODIC, periodic)
            .putBoolean(KEY_PRERELEASES, prereleases)
            .apply()
        UpdateCheckScheduler.sync(context)
    }

    fun shouldNotify(context: Context, versionCode: Int): Boolean {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_LAST_NOTIFIED, 0) >= versionCode) return false
        preferences.edit().putInt(KEY_LAST_NOTIFIED, versionCode).apply()
        return true
    }
}

internal object UpdateCheckScheduler {
    private const val UNIQUE_WORK = "foxconnect_update_metadata_check_v1"

    fun sync(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!UpdatePreferences.periodicChecks(context)) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

internal class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!UpdatePreferences.periodicChecks(applicationContext)) return Result.success()
        return when (
            val result = UpdateRepository(applicationContext).check(
                UpdatePreferences.includePrereleases(applicationContext),
            )
        ) {
            is UpdateCheckResult.Available -> {
                if (UpdatePreferences.shouldNotify(applicationContext, result.update.versionCode)) {
                    notifyAvailable(result.update)
                }
                Result.success()
            }
            is UpdateCheckResult.Failure,
            UpdateCheckResult.UpToDate,
            -> Result.success()
        }
    }

    private fun notifyAvailable(update: AvailableUpdate) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = applicationContext.getString(R.string.update_notification_channel_description)
                    setShowBadge(true)
                },
            )
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_UPDATE_SETTINGS
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fox)
            .setContentTitle(applicationContext.getString(R.string.update_notification_title))
            .setContentText(
                applicationContext.getString(
                    R.string.update_notification_body,
                    update.releaseName,
                    update.versionCode,
                ),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "available_updates"
        const val NOTIFICATION_ID = 4107
    }
}
