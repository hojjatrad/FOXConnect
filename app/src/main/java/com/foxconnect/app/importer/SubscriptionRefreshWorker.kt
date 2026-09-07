package com.foxconnect.app.importer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxconnect.app.FoxConnectApplication
import com.foxconnect.core.engine.AndroidTunnelController
import com.foxconnect.core.engine.TunnelRuntime
import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.parser.UniversalConfigImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Periodically refreshes enabled subscriptions without exposing URLs or configs to logs/output data. */
class SubscriptionRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? FoxConnectApplication ?: return Result.failure()
        val repository = application.profileRepository
        val loaded = repository.state.first { it.loaded }
        if (loaded.errorCode != null) return Result.failure()
        var retryTransientFailure = false

        loaded.subscriptions.filter { it.enabled }.forEach { initial ->
            val current = repository.state.value
            val subscription = current.subscriptions.firstOrNull { it.id == initial.id && it.enabled }
                ?: return@forEach
            val selectedBelongsToSubscription = current.selectedProfile?.subscriptionId == subscription.id
            if (selectedBelongsToSubscription && TunnelRuntime.snapshot.value.state.isTunnelActive()) {
                return@forEach
            }

            when (val fetched = SubscriptionClient().fetch(subscription.url, subscription.etag)) {
                SubscriptionFetchResult.NotModified -> repository.markSubscriptionChecked(subscription.id)
                is SubscriptionFetchResult.Failure -> {
                    repository.markSubscriptionFailure(subscription.id, fetched.errorCode)
                    retryTransientFailure = retryTransientFailure || fetched.errorCode.isTransientFailure()
                }
                is SubscriptionFetchResult.Success -> {
                    val batch = withContext(Dispatchers.Default) {
                        UniversalConfigImporter.importBytes(fetched.body, subscription.name)
                    }
                    if (batch.configs.isEmpty()) {
                        repository.markSubscriptionFailure(subscription.id, "subscription_no_supported_configs")
                    } else {
                        val sync = repository.syncSubscription(
                            subscription.name,
                            subscription.url,
                            batch.configs,
                            fetched.etag,
                        )
                        if (sync.isSuccess) {
                            if (selectedBelongsToSubscription) {
                                AndroidTunnelController(applicationContext).clearPersistedConnection()
                            }
                        } else {
                            repository.markSubscriptionFailure(subscription.id, "subscription_storage_error")
                        }
                    }
                }
            }
        }
        return if (retryTransientFailure) Result.retry() else Result.success()
    }

    private fun ConnectionState.isTunnelActive(): Boolean =
        this is ConnectionState.Connected || this is ConnectionState.Connecting || this is ConnectionState.Switching

    private fun String.isTransientFailure(): Boolean =
        this == "subscription_network_error" ||
            this == "subscription_http_408" ||
            this == "subscription_http_429" ||
            removePrefix("subscription_http_").toIntOrNull()?.let { it >= 500 } == true
}

object SubscriptionRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "foxconnect_subscription_refresh_v1"
    private const val REPEAT_HOURS = 24L
    private const val RETRY_MINUTES = 30L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(REPEAT_HOURS, TimeUnit.HOURS)
            .setInitialDelay(REPEAT_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MINUTES, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }
}
