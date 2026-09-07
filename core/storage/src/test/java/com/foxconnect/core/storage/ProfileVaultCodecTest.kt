package com.foxconnect.core.storage

import com.foxconnect.core.model.ManagedProfile
import com.foxconnect.core.model.ProfileSource
import com.foxconnect.core.model.ProfileVault
import com.foxconnect.core.model.ProtocolType
import com.foxconnect.core.parser.UniversalConfigImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileVaultCodecTest {
    @Test
    fun roundTripPreservesProfileFields() {
        val rawConfig = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Test"
        val imported = UniversalConfigImporter.importText(rawConfig).configs.single()
        val profile = ManagedProfile(
            id = "record-1",
            connectionId = imported.profile.id,
            name = "Test",
            protocol = ProtocolType.VLESS,
            rawConfig = rawConfig,
            source = ProfileSource.CLIPBOARD,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )
        val encoded = ProfileVaultCodec.encode(ProfileVault(profiles = listOf(profile), selectedProfileId = profile.id))
        val decoded = ProfileVaultCodec.decode(encoded)

        assertEquals(profile, decoded.profiles.single())
        assertEquals(profile.id, decoded.selectedProfileId)
        assertFalse(encoded.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsConfigurationIdentityMismatchOnDecode() {
        val rawConfig = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Test"
        val profile = ManagedProfile(
            id = "record-1",
            connectionId = "wrong-connection-id",
            name = "Test",
            protocol = ProtocolType.VLESS,
            rawConfig = rawConfig,
            source = ProfileSource.FILE,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

        ProfileVaultCodec.decode(ProfileVaultCodec.encode(ProfileVault(profiles = listOf(profile))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateRecordIds() {
        val profile = ManagedProfile(
            id = "same",
            connectionId = "connection",
            name = "Test",
            protocol = ProtocolType.VLESS,
            rawConfig = "vless://secret@example.com:443",
            source = ProfileSource.FILE,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
        val invalid = ProfileVault(profiles = listOf(profile, profile.copy(connectionId = "other")))

        ProfileVaultCodec.decode(ProfileVaultCodec.encode(invalid))
    }
}
