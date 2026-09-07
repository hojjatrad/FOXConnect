package com.foxconnect.core.storage

import com.foxconnect.core.model.ProfileSource
import com.foxconnect.core.model.ProfileVault
import com.foxconnect.core.parser.UniversalConfigImporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryTest {
    private val first = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#First"
    private val second = "vless://22222222-2222-4222-8222-222222222222@vpn.example:8443?type=ws&path=%2Fws#Second"

    @Test
    fun importPersistsSelectsAndDeduplicates() = runTest {
        val store = MemoryStore()
        val repository = ProfileRepository(store, this)
        advanceUntilIdle()
        val batch = UniversalConfigImporter.importText("$first\n$second")

        val initial = repository.import(batch.configs, ProfileSource.CLIPBOARD).getOrThrow()
        val repeated = repository.import(batch.configs, ProfileSource.FILE).getOrThrow()

        assertEquals(2, initial.added)
        assertEquals(2, repeated.updated)
        assertEquals(2, repository.state.value.profiles.size)
        assertTrue(repository.state.value.selectedProfileId != null)
        assertEquals(2, store.vault.profiles.size)
    }

    @Test
    fun seventeenProfileSubscriptionPersistsAndRemainsVisible() = runTest {
        val store = MemoryStore()
        val repository = ProfileRepository(store, this)
        advanceUntilIdle()
        val payload = (1..17).joinToString("\n") { index ->
            val id = java.util.UUID.nameUUIDFromBytes("sanitized-repository-fixture-$index".toByteArray())
            "vless://$id@192.0.2.$index:443?security=tls&sni=example.invalid#Node-$index"
        }
        val imported = UniversalConfigImporter.importText(payload)

        val summary = repository.syncSubscription(
            "Sanitized regression",
            "https://example.invalid/subscription",
            imported.configs,
            "fixture-etag",
        ).getOrThrow()

        assertEquals(17, summary.added)
        assertEquals(17, repository.state.value.profiles.size)
        assertEquals(17, store.vault.profiles.size)
        assertEquals(1, repository.state.value.subscriptions.size)
    }

    @Test
    fun explicitDuplicateGetsIndependentRecordId() = runTest {
        val repository = ProfileRepository(MemoryStore(), this)
        advanceUntilIdle()
        repository.import(UniversalConfigImporter.importText(first).configs, ProfileSource.CLIPBOARD)
        val original = repository.state.value.profiles.single()

        assertEquals(ProfileMutationResult.Success, repository.duplicate(original.id, "Copy"))

        val records = repository.state.value.profiles
        assertEquals(2, records.size)
        assertNotEquals(records[0].id, records[1].id)
        assertEquals(records[0].connectionId, records[1].connectionId)
    }

    @Test
    fun subscriptionSyncRemovesOnlyStaleManagedProfiles() = runTest {
        val repository = ProfileRepository(MemoryStore(), this)
        advanceUntilIdle()
        val firstSync = repository.syncSubscription(
            "Remote",
            "https://example.com/sub",
            UniversalConfigImporter.importText("$first\n$second").configs,
            "v1",
        ).getOrThrow()

        val secondSync = repository.syncSubscription(
            "Remote",
            "https://example.com/sub",
            UniversalConfigImporter.importText(second).configs,
            "v2",
        ).getOrThrow()

        assertEquals(firstSync.subscriptionId, secondSync.subscriptionId)
        assertEquals(1, secondSync.removed)
        assertEquals(1, repository.state.value.profiles.size)
        assertEquals("v2", repository.state.value.subscriptions.single().etag)
    }

    @Test
    fun encryptedBackupRestoresAtomicallyAndClearsPasswordArrays() = runTest {
        val repository = ProfileRepository(MemoryStore(), this)
        advanceUntilIdle()
        repository.import(UniversalConfigImporter.importText(first).configs, ProfileSource.CLIPBOARD)
        val originalId = repository.state.value.profiles.single().id
        val exportPassword = "correct horse battery staple".toCharArray()
        val backup = repository.exportEncryptedBackup(exportPassword).getOrThrow()
        assertTrue(exportPassword.all { it == '\u0000' })

        repository.delete(originalId)
        assertTrue(repository.state.value.profiles.isEmpty())
        val restorePassword = "correct horse battery staple".toCharArray()
        val summary = repository.restoreEncryptedBackup(backup, restorePassword).getOrThrow()

        assertEquals(1, summary.profiles)
        assertEquals(originalId, repository.state.value.selectedProfileId)
        assertTrue(restorePassword.all { it == '\u0000' })
    }

    @Test
    fun failedFirstSubscriptionRemainsVisibleAndCanLaterSync() = runTest {
        val repository = ProfileRepository(MemoryStore(), this)
        advanceUntilIdle()

        val id = repository.upsertSubscription(
            "Remote",
            "https://example.com/sub",
            "subscription_no_supported_configs",
        ).getOrThrow()

        assertEquals(id, repository.state.value.subscriptions.single().id)
        assertEquals("subscription_no_supported_configs", repository.state.value.subscriptions.single().lastError)
        val synced = repository.syncSubscription(
            "Remote",
            "https://example.com/sub",
            UniversalConfigImporter.importText(first).configs,
            "v1",
        ).getOrThrow()
        assertEquals(id, synced.subscriptionId)
        assertEquals(null, repository.state.value.subscriptions.single().lastError)
        assertEquals(1, repository.state.value.profiles.size)
    }

    @Test
    fun subscriptionCanBeDisabledAndRecordsBoundedFailure() = runTest {
        val repository = ProfileRepository(MemoryStore(), this)
        advanceUntilIdle()
        val sync = repository.syncSubscription(
            "Remote",
            "https://example.com/sub",
            UniversalConfigImporter.importText(first).configs,
            "v1",
        ).getOrThrow()

        assertEquals(ProfileMutationResult.Success, repository.setSubscriptionEnabled(sync.subscriptionId, false))
        assertEquals(
            ProfileMutationResult.Success,
            repository.markSubscriptionFailure(sync.subscriptionId, "network error: secret detail"),
        )

        val subscription = repository.state.value.subscriptions.single()
        assertFalse(subscription.enabled)
        assertEquals("networkerrorsecretdetail", subscription.lastError)
    }

    private class MemoryStore : ProfileVaultStore {
        var vault = ProfileVault()
        override fun read(): VaultReadResult = VaultReadResult.Success(vault)
        override fun write(vault: ProfileVault) {
            this.vault = vault
        }
    }
}
