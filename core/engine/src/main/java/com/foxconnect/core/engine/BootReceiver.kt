package com.foxconnect.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.foxconnect.core.parser.ConnectableProfileParser
import com.foxconnect.core.parser.ParseResult
import com.foxconnect.core.storage.EncryptedProfileStore
import com.foxconnect.core.storage.VaultReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal const val DEFAULT_AUTO_CONNECT = false
internal fun isBootRestoreAction(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED

/** Restores the selected tunnel only after boot and only under explicit policy. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isBootRestoreAction(intent.action)) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                restore(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun restore(context: Context) {
        val autoConnect = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CONNECT_KEY, DEFAULT_AUTO_CONNECT)
        if (!autoConnect || VpnService.prepare(context) != null) return
        val activeStore = EngineConfigStore(context)
        if (activeStore.read() == null && !restoreSelectedProfile(context, activeStore)) return

        TunnelRunAuthorization(context).authorize()
        val serviceIntent = Intent(context, FoxVpnService::class.java)
            .setAction(FoxVpnService.ACTION_CONNECT)
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
            .onFailure { TunnelRunAuthorization(context).revoke() }
    }

    private fun restoreSelectedProfile(context: Context, activeStore: EngineConfigStore): Boolean {
        val vault = (EncryptedProfileStore(context).read() as? VaultReadResult.Success)?.vault ?: return false
        val selectedId = vault.selectedProfileId ?: return false
        val ordered = vault.profiles.sortedWith(
            compareByDescending<com.foxconnect.core.model.ManagedProfile> { it.id == selectedId }
                .thenByDescending { it.favorite }
                .thenByDescending { it.updatedAtEpochMs },
        )
        val candidates = ordered.asSequence().mapNotNull { record ->
            val profile = when (val parsed = ConnectableProfileParser.parse(record.rawConfig)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Error -> return@mapNotNull null
            }
            runCatching {
                PendingEngineCandidate(
                    id = record.id,
                    profileName = record.name,
                    protocol = profile.protocol,
                    json = SingBoxConfigFactory.fromProfile(profile),
                )
            }.getOrNull()
        }.take(PendingEngineConfig.MAX_FAILOVER_CANDIDATES).toList()
        if (candidates.firstOrNull()?.id != selectedId) return false
        activeStore.write(PendingEngineConfig(candidates))
        return true
    }

    private companion object {
        const val PREFERENCES = "product_preferences"
        const val AUTO_CONNECT_KEY = "auto_connect"
    }
}
