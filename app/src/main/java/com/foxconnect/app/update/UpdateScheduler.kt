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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxconnect.app.BuildConfig
import com.foxconnect.app.MainActivity
import com.foxconnect.app.R
import java.util.concurrent.TimeUnit

internal object UpdatePreferences {
    private const val FILE = "update_preferences"
    private const val KEY_PERIODIC = "periodic_checks"
    private const val KEY_PRERELEASES = "include_prereleases"
    private const val KEY_LAST_NOTIFIED = "last_notified_version"
    private const val KEY_POLICY_REVISION = "policy_revision"

    fun migrateDefaults(context: Context) {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val revision = preferences.getInt(KEY_POLICY_REVISION, 0)
        if (revision >= UpdatePolicy.CURRENT_POLICY_REVISION) return
        val storedPeriodic = if (preferences.contains(KEY_PERIODIC)) {
            preferences.getBoolean(KEY_PERIODIC, false)
        } else {
            null
        }
        val storedPrereleases = if (preferences.contains(KEY_PRERELEASES)) {
            preferences.getBoolean(KEY_PRERELEASES, false)
        } else {
            null
        }
        preferences.edit()
            .putBoolean(
                KEY_PERIODIC,
                UpdatePolicy.migratedPeriodicChecks(revision, storedPeriodic),
            )
            .putBoolean(
                KEY_PRERELEASES,
                UpdatePolicy.migratedPrereleases(
                    revision,
                    storedPrereleases,
                    BuildConfig.UPDATE_ASSET_CHANNEL,
                ),
            )
            .putInt(KEY_POLICY_REVISION, UpdatePolicy.CURRENT_POLICY_REVISION)
            .apply()
    }

    fun periodicChecks(context: Context): Boolean {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stored = if (preferences.contains(KEY_PERIODIC)) {
            preferences.getBoolean(KEY_PERIODIC, true)
        } else {
            null
        }
        return UpdatePolicy.periodicChecks(stored)
    }

    fun includePrereleases(context: Context): Boolean {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stored = if (preferences.contains(KEY_PRERELEASES)) {
            preferences.getBoolean(KEY_PRERELEASES, false)
        } else {
            null
        }
        return UpdatePolicy.includePrereleases(stored, BuildConfig.UPDATE_ASSET_CHANNEL)
    }

    fun savePolicy(context: Context, periodic: Boolean, prereleases: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PERIODIC, periodic)
            .putBoolean(KEY_PRERELEASES, prereleases)
            .apply()
        UpdateCheckScheduler.sync(context)
    }

    fun shouldNotify(context: Context, versionCode: Int): Boolean {
        val lastNotified = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_NOTIFIED, 0)
        return UpdatePolicy.shouldNotify(lastNotified, versionCode)
    }

    fun markNotified(context: Context, versionCode: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LAST_NOTIFIED, versionCode)
            .apply()
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
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            UpdatePolicy.CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS,
            UpdatePolicy.FLEX_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(UpdatePolicy.INITIAL_CHECK_DELAY_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                UpdatePolicy.RETRY_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
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
                if (
                    UpdatePreferences.shouldNotify(applicationContext, result.update.versionCode) &&
                    notifyAvailable(result.update)
                ) {
                    // Never consume a version while notifications are unavailable:
                    // a later run must still be able to alert the user.
                    UpdatePreferences.markNotified(applicationContext, result.update.versionCode)
                }
                Result.success()
            }
            is UpdateCheckResult.Failure -> {
                if (result.reason == UpdateFailure.NETWORK) Result.retry() else Result.success()
            }
            UpdateCheckResult.UpToDate -> Result.success()
        }
    }

    private fun notifyAvailable(update: AvailableUpdate): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
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
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return false
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
            .addAction(
                0,
                applicationContext.getString(R.string.update_notification_action),
                pendingIntent,
            )
            .build()
        return runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val CHANNEL_ID = "available_updates"
        const val NOTIFICATION_ID = 4107
    }
}
