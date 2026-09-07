package com.foxconnect.core.storage

import com.foxconnect.core.model.ManagedProfile
import com.foxconnect.core.model.ProfileSource
import com.foxconnect.core.model.ProfileVault
import com.foxconnect.core.model.ProtocolType
import com.foxconnect.core.model.SubscriptionRecord
import com.foxconnect.core.parser.UniversalConfigImporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class EncryptedProfileBackupTest {
    @Test
    fun encryptedBackupRoundTripsAndUsesRandomSalt() {
        val vault = sampleVault()
        val first = EncryptedProfileBackup.encrypt(vault, "correct horse battery staple".toCharArray())
        val second = EncryptedProfileBackup.encrypt(vault, "correct horse battery staple".toCharArray())

        assertFalse(first.contentEquals(second))
        assertEquals(
            vault,
            EncryptedProfileBackup.decrypt(first, "correct horse battery staple".toCharArray()),
        )
    }

    @Test
    fun wrongPassphraseAndTamperingFailClosed() {
        val backup = EncryptedProfileBackup.encrypt(sampleVault(), "a sufficiently long passphrase".toCharArray())
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedProfileBackup.decrypt(backup, "a different long passphrase".toCharArray())
        }

        val tampered = backup.copyOf().also { it[it.lastIndex] = (it.last() xor 0x01) }
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedProfileBackup.decrypt(tampered, "a sufficiently long passphrase".toCharArray())
        }
        assertArrayEquals(backup, backup.copyOf())
    }

    @Test
    fun rejectsOversizedEnvelopeBeforeKeyDerivation() {
        val oversized = ByteArray(EncryptedProfileBackup.MAX_ENVELOPE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedProfileBackup.decrypt(oversized, "a sufficiently long passphrase".toCharArray())
        }
    }

    private fun sampleVault(): ProfileVault {
        val subscription = SubscriptionRecord(
            id = "subscription-1",
            name = "Private subscription",
            url = "https://vpn.example/subscription",
            etag = "etag-1",
        )
        val rawConfig = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Private"
        val imported = UniversalConfigImporter.importText(rawConfig).configs.single()
        val profile = ManagedProfile(
            id = "profile-1",
            connectionId = imported.profile.id,
            name = "Private VLESS",
            protocol = ProtocolType.VLESS,
            rawConfig = rawConfig,
            source = ProfileSource.SUBSCRIPTION,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            subscriptionId = subscription.id,
            favorite = true,
        )
        return ProfileVault(
            profiles = listOf(profile),
            subscriptions = listOf(subscription),
            selectedProfileId = profile.id,
        )
    }

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()
}
