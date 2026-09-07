package com.foxconnect.core.storage

import com.foxconnect.core.model.ManagedProfile
import com.foxconnect.core.model.ProfileSource
import com.foxconnect.core.model.ProfileVault
import com.foxconnect.core.model.SubscriptionRecord
import com.foxconnect.core.parser.ImportedConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class ProfileRepositoryState(
    val loaded: Boolean = false,
    val profiles: List<ManagedProfile> = emptyList(),
    val subscriptions: List<SubscriptionRecord> = emptyList(),
    val selectedProfileId: String? = null,
    val errorCode: String? = null,
) {
    val selectedProfile: ManagedProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }
}

data class ProfileImportSummary(
    val added: Int,
    val updated: Int,
)

data class SubscriptionSyncSummary(
    val subscriptionId: String,
    val added: Int,
    val updated: Int,
    val removed: Int,
)

data class ProfileRestoreSummary(
    val profiles: Int,
    val subscriptions: Int,
)

sealed interface ProfileMutationResult {
    data object Success : ProfileMutationResult
    data class Failure(val errorCode: String) : ProfileMutationResult
}

class ProfileRepository(
    private val store: ProfileVaultStore,
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(ProfileRepositoryState())
    private var vault: ProfileVault? = null

    val state: StateFlow<ProfileRepositoryState> = mutableState.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    suspend fun import(
        configs: List<ImportedConfig>,
        source: ProfileSource,
        subscriptionId: String? = null,
    ): Result<ProfileImportSummary> = mutateWithResult { current ->
        val now = System.currentTimeMillis()
        val profiles = current.profiles.toMutableList()
        var added = 0
        var updated = 0
        configs.forEach { imported ->
            require(imported.rawConfig.length <= MAX_RAW_CONFIG_CHARS) { "Config is too large" }
            val index = profiles.indexOfFirst {
                it.protocol == imported.profile.protocol && it.connectionId == imported.profile.id
            }
            if (index >= 0) {
                val existing = profiles[index]
                profiles[index] = existing.copy(
                    rawConfig = imported.rawConfig.trim(),
                    updatedAtEpochMs = now,
                    subscriptionId = subscriptionId ?: existing.subscriptionId,
                )
                updated++
            } else {
                profiles += ManagedProfile(
                    id = UUID.randomUUID().toString(),
                    connectionId = imported.profile.id,
                    name = imported.profile.name.take(MAX_NAME_CHARS),
                    protocol = imported.profile.protocol,
                    rawConfig = imported.rawConfig.trim(),
                    source = source,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    subscriptionId = subscriptionId,
                )
                added++
            }
        }
        val selected = current.selectedProfileId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        current.copy(profiles = profiles, selectedProfileId = selected) to ProfileImportSummary(added, updated)
    }

    suspend fun syncSubscription(
        name: String,
        url: String,
        configs: List<ImportedConfig>,
        etag: String?,
    ): Result<SubscriptionSyncSummary> = mutateWithResult { current ->
        require(configs.isNotEmpty()) { "Subscription contains no supported configs" }
        require(url.length <= MAX_URL_CHARS) { "Subscription URL is too large" }
        val cleanName = name.trim().take(MAX_NAME_CHARS)
        require(cleanName.isNotBlank()) { "Subscription name is empty" }
        val now = System.currentTimeMillis()
        val existingSubscription = current.subscriptions.firstOrNull { it.url == url }
        val subscriptionId = existingSubscription?.id ?: UUID.randomUUID().toString()
        val remoteConnections = configs.map { it.profile.protocol to it.profile.id }.toSet()
        val oldManaged = current.profiles.filter { it.subscriptionId == subscriptionId }
        val profiles = current.profiles
            .filterNot {
                it.subscriptionId == subscriptionId && (it.protocol to it.connectionId) !in remoteConnections
            }
            .toMutableList()
        val removed = oldManaged.count { (it.protocol to it.connectionId) !in remoteConnections }
        var added = 0
        var updated = 0
        configs.forEach { imported ->
            require(imported.rawConfig.length <= MAX_RAW_CONFIG_CHARS) { "Config is too large" }
            val managedIndex = profiles.indexOfFirst {
                it.subscriptionId == subscriptionId &&
                    it.protocol == imported.profile.protocol &&
                    it.connectionId == imported.profile.id
            }
            if (managedIndex >= 0) {
                val existing = profiles[managedIndex]
                profiles[managedIndex] = existing.copy(
                    name = imported.profile.name.take(MAX_NAME_CHARS),
                    rawConfig = imported.rawConfig.trim(),
                    updatedAtEpochMs = now,
                )
                updated++
            } else if (profiles.none {
                it.protocol == imported.profile.protocol && it.connectionId == imported.profile.id
            }) {
                profiles += ManagedProfile(
                    id = UUID.randomUUID().toString(),
                    connectionId = imported.profile.id,
                    name = imported.profile.name.take(MAX_NAME_CHARS),
                    protocol = imported.profile.protocol,
                    rawConfig = imported.rawConfig.trim(),
                    source = ProfileSource.SUBSCRIPTION,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    subscriptionId = subscriptionId,
                )
                added++
            } else {
                updated++
            }
        }
        val record = (existingSubscription ?: SubscriptionRecord(
            id = subscriptionId,
            name = cleanName,
            url = url,
        )).copy(
            name = cleanName,
            etag = etag,
            lastUpdatedAtEpochMs = now,
            lastError = null,
        )
        val subscriptions = current.subscriptions.filterNot { it.id == subscriptionId } + record
        val selected = current.selectedProfileId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        current.copy(
            profiles = profiles,
            subscriptions = subscriptions,
            selectedProfileId = selected,
        ) to SubscriptionSyncSummary(subscriptionId, added, updated, removed)
    }

    /**
     * Persists a subscription before or after a failed first fetch so it remains
     * visible and can be corrected/refreshed instead of silently disappearing.
     */
    suspend fun upsertSubscription(
        name: String,
        url: String,
        errorCode: String? = null,
    ): Result<String> = mutateWithResult { current ->
        require(url.length <= MAX_URL_CHARS) { "Subscription URL is too large" }
        val cleanName = name.trim().take(MAX_NAME_CHARS)
        require(cleanName.isNotBlank()) { "Subscription name is empty" }
        val existing = current.subscriptions.firstOrNull { it.url == url }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val safeError = errorCode?.sanitizeErrorCode()
        val record = (existing ?: SubscriptionRecord(id = id, name = cleanName, url = url)).copy(
            name = cleanName,
            lastError = safeError,
        )
        current.copy(subscriptions = current.subscriptions.filterNot { it.id == id } + record) to id
    }

    suspend fun deleteSubscription(subscriptionId: String): ProfileMutationResult = mutate { current ->
        if (current.subscriptions.none { it.id == subscriptionId }) return@mutate null
        val profiles = current.profiles.filterNot { it.subscriptionId == subscriptionId }
        val selected = current.selectedProfileId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        current.copy(
            profiles = profiles,
            subscriptions = current.subscriptions.filterNot { it.id == subscriptionId },
            selectedProfileId = selected,
        )
    }

    suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean): ProfileMutationResult =
        mutate { current ->
            val index = current.subscriptions.indexOfFirst { it.id == subscriptionId }
            if (index < 0) return@mutate null
            val subscriptions = current.subscriptions.toMutableList()
            subscriptions[index] = subscriptions[index].copy(enabled = enabled)
            current.copy(subscriptions = subscriptions)
        }

    suspend fun markSubscriptionChecked(subscriptionId: String): ProfileMutationResult = mutate { current ->
        val index = current.subscriptions.indexOfFirst { it.id == subscriptionId }
        if (index < 0) return@mutate null
        val subscriptions = current.subscriptions.toMutableList()
        subscriptions[index] = subscriptions[index].copy(
            lastUpdatedAtEpochMs = System.currentTimeMillis(),
            lastError = null,
        )
        current.copy(subscriptions = subscriptions)
    }

    suspend fun markSubscriptionFailure(subscriptionId: String, errorCode: String): ProfileMutationResult =
        mutate { current ->
            val index = current.subscriptions.indexOfFirst { it.id == subscriptionId }
            if (index < 0) return@mutate null
            val subscriptions = current.subscriptions.toMutableList()
            subscriptions[index] = subscriptions[index].copy(lastError = errorCode.sanitizeErrorCode())
            current.copy(subscriptions = subscriptions)
        }

    suspend fun exportEncryptedBackup(passphrase: CharArray): Result<ByteArray> {
        initialized.await()
        return try {
            val snapshot = mutex.withLock {
                vault ?: return@withLock null
            } ?: return Result.failure(IllegalStateException("vault_unavailable"))
            resultOf {
                withContext(Dispatchers.Default) {
                    EncryptedProfileBackup.encrypt(snapshot, passphrase)
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun restoreEncryptedBackup(
        envelope: ByteArray,
        passphrase: CharArray,
    ): Result<ProfileRestoreSummary> {
        initialized.await()
        return try {
            val restoredResult = resultOf {
                withContext(Dispatchers.Default) {
                    EncryptedProfileBackup.decrypt(envelope, passphrase)
                }
            }
            val restored = restoredResult.getOrElse { return Result.failure(it) }
            mutex.withLock {
                if (vault == null) return@withLock Result.failure(IllegalStateException("vault_unavailable"))
                resultOf {
                    persist(restored).getOrThrow()
                    ProfileRestoreSummary(restored.profiles.size, restored.subscriptions.size)
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun select(profileId: String): ProfileMutationResult = mutate { current ->
        if (current.profiles.none { it.id == profileId }) return@mutate null
        current.copy(selectedProfileId = profileId)
    }

    suspend fun rename(profileId: String, newName: String): ProfileMutationResult {
        val name = newName.trim().take(MAX_NAME_CHARS)
        if (name.isBlank()) return ProfileMutationResult.Failure("invalid_name")
        return mutate { current ->
            val index = current.profiles.indexOfFirst { it.id == profileId }
            if (index < 0) return@mutate null
            val profiles = current.profiles.toMutableList()
            profiles[index] = profiles[index].copy(name = name, updatedAtEpochMs = System.currentTimeMillis())
            current.copy(profiles = profiles)
        }
    }

    suspend fun updateConfig(profileId: String, imported: ImportedConfig): ProfileMutationResult = mutate { current ->
        val index = current.profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return@mutate null
        val existing = current.profiles[index]
        if (
            (imported.profile.id != existing.connectionId || imported.profile.protocol != existing.protocol) &&
            current.profiles.any {
                it.id != profileId &&
                    it.protocol == imported.profile.protocol &&
                    it.connectionId == imported.profile.id
            }
        ) {
            return@mutate null
        }
        val profiles = current.profiles.toMutableList()
        profiles[index] = profiles[index].copy(
            connectionId = imported.profile.id,
            name = imported.profile.name.take(MAX_NAME_CHARS),
            protocol = imported.profile.protocol,
            rawConfig = imported.rawConfig.trim(),
            source = ProfileSource.MANUAL,
            subscriptionId = null,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        current.copy(profiles = profiles)
    }

    suspend fun setFavorite(profileId: String, favorite: Boolean): ProfileMutationResult = mutate { current ->
        val index = current.profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return@mutate null
        val profiles = current.profiles.toMutableList()
        profiles[index] = profiles[index].copy(favorite = favorite, updatedAtEpochMs = System.currentTimeMillis())
        current.copy(profiles = profiles)
    }

    suspend fun duplicate(profileId: String, copyName: String): ProfileMutationResult = mutate { current ->
        val source = current.profiles.firstOrNull { it.id == profileId } ?: return@mutate null
        val now = System.currentTimeMillis()
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = copyName.trim().ifBlank { source.name }.take(MAX_NAME_CHARS),
            source = ProfileSource.MANUAL,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            subscriptionId = null,
            favorite = false,
        )
        current.copy(profiles = current.profiles + duplicate, selectedProfileId = duplicate.id)
    }

    suspend fun delete(profileId: String): ProfileMutationResult = mutate { current ->
        if (current.profiles.none { it.id == profileId }) return@mutate null
        val profiles = current.profiles.filterNot { it.id == profileId }
        val selected = if (current.selectedProfileId == profileId) profiles.firstOrNull()?.id else current.selectedProfileId
        current.copy(profiles = profiles, selectedProfileId = selected)
    }

    private suspend fun load() {
        mutex.withLock {
            when (val result = store.read()) {
                is VaultReadResult.Success -> {
                    vault = result.vault
                    publish(result.vault, errorCode = null)
                }
                is VaultReadResult.Failure -> {
                    vault = null
                    mutableState.value = ProfileRepositoryState(loaded = true, errorCode = result.errorCode)
                }
            }
            initialized.complete(Unit)
        }
    }

    private suspend fun mutate(transform: (ProfileVault) -> ProfileVault?): ProfileMutationResult {
        initialized.await()
        return mutex.withLock {
            val current = vault ?: return@withLock ProfileMutationResult.Failure("vault_unavailable")
            val next = transform(current) ?: return@withLock ProfileMutationResult.Failure("profile_not_found")
            persist(next).fold(
                onSuccess = { ProfileMutationResult.Success },
                onFailure = { ProfileMutationResult.Failure("vault_write_failed") },
            )
        }
    }

    private suspend fun <T> mutateWithResult(transform: (ProfileVault) -> Pair<ProfileVault, T>): Result<T> {
        initialized.await()
        return mutex.withLock {
            val current = vault ?: return@withLock Result.failure(IllegalStateException("vault_unavailable"))
            runCatching {
                val (next, value) = transform(current)
                persist(next).getOrThrow()
                value
            }
        }
    }

    private suspend fun persist(next: ProfileVault): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            store.write(next)
            vault = next
            publish(next, errorCode = null)
        }
    }

    private fun publish(value: ProfileVault, errorCode: String?) {
        mutableState.value = ProfileRepositoryState(
            loaded = true,
            profiles = value.profiles.sortedWith(
                compareByDescending<ManagedProfile> { it.favorite }.thenByDescending { it.updatedAtEpochMs },
            ),
            subscriptions = value.subscriptions.sortedBy { it.name.lowercase() },
            selectedProfileId = value.selectedProfileId,
            errorCode = errorCode,
        )
    }

    private fun String.sanitizeErrorCode(): String =
        filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(MAX_ERROR_CODE_CHARS)
            .ifBlank { "subscription_error" }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        const val MAX_NAME_CHARS = 120
        const val MAX_URL_CHARS = 4 * 1024
        const val MAX_RAW_CONFIG_CHARS = 16 * 1024
        const val MAX_ERROR_CODE_CHARS = 128
    }
}
