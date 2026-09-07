package com.foxconnect.core.storage

import com.foxconnect.core.model.ProfileVault
import com.foxconnect.core.parser.UniversalConfigImporter
import kotlinx.serialization.json.Json
import java.net.URI

object ProfileVaultCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(vault: ProfileVault): ByteArray {
        validate(vault)
        return json.encodeToString(ProfileVault.serializer(), vault).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_PLAINTEXT_BYTES) { "Profile vault is too large" }
        }
    }

    fun decode(bytes: ByteArray): ProfileVault {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Profile vault is too large" }
        return json.decodeFromString(ProfileVault.serializer(), bytes.toString(Charsets.UTF_8)).also { vault ->
            validate(vault)
            vault.profiles.forEach { profile ->
                val imported = UniversalConfigImporter.importText(profile.rawConfig).configs.singleOrNull()
                require(
                    imported != null &&
                        imported.rawConfig == profile.rawConfig.trim() &&
                        imported.profile.id == profile.connectionId &&
                        imported.profile.protocol == profile.protocol,
                ) { "Invalid profile configuration identity" }
            }
        }
    }

    private fun validate(vault: ProfileVault) {
        require(vault.schemaVersion == CURRENT_SCHEMA) { "Unsupported profile vault schema" }
        require(vault.profiles.size <= MAX_PROFILES) { "Too many profiles" }
        require(vault.subscriptions.size <= MAX_SUBSCRIPTIONS) { "Too many subscriptions" }
        require(vault.profiles.map { it.id }.toSet().size == vault.profiles.size) { "Duplicate profile IDs" }
        require(vault.subscriptions.map { it.id }.toSet().size == vault.subscriptions.size) {
            "Duplicate subscription IDs"
        }
        require(vault.subscriptions.map { it.url }.toSet().size == vault.subscriptions.size) {
            "Duplicate subscription URLs"
        }
        val profileIds = vault.profiles.mapTo(mutableSetOf()) { it.id }
        val subscriptionIds = vault.subscriptions.mapTo(mutableSetOf()) { it.id }
        require(vault.selectedProfileId == null || vault.selectedProfileId in profileIds) {
            "Selected profile does not exist"
        }
        vault.profiles.forEach { profile ->
            require(profile.id.isNotBlank() && profile.id.length <= MAX_ID_CHARS) { "Invalid profile ID" }
            require(profile.connectionId.isNotBlank() && profile.connectionId.length <= MAX_CONNECTION_ID_CHARS) {
                "Invalid profile connection ID"
            }
            require(profile.name.isNotBlank() && profile.name.length <= MAX_NAME_CHARS) { "Invalid profile name" }
            require(profile.rawConfig.isNotBlank() && profile.rawConfig.length <= MAX_RAW_CONFIG_CHARS) {
                "Invalid profile configuration"
            }
            require(profile.createdAtEpochMs >= 0 && profile.updatedAtEpochMs >= 0) {
                "Invalid profile timestamp"
            }
            require(profile.subscriptionId == null || profile.subscriptionId in subscriptionIds) {
                "Profile references a missing subscription"
            }
        }
        vault.subscriptions.forEach { subscription ->
            require(subscription.id.isNotBlank() && subscription.id.length <= MAX_ID_CHARS) {
                "Invalid subscription ID"
            }
            require(subscription.name.isNotBlank() && subscription.name.length <= MAX_NAME_CHARS) {
                "Invalid subscription name"
            }
            require(subscription.url.length <= MAX_URL_CHARS && isStrictHttpsUrl(subscription.url)) {
                "Invalid subscription URL"
            }
            require(subscription.etag?.let { it.length <= MAX_ETAG_CHARS } != false) {
                "Invalid subscription ETag"
            }
            require(subscription.lastError?.let { it.length <= MAX_ERROR_CHARS } != false) {
                "Invalid subscription error"
            }
            require(subscription.lastUpdatedAtEpochMs?.let { it >= 0 } != false) {
                "Invalid subscription timestamp"
            }
        }
    }

    private fun isStrictHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)

    const val CURRENT_SCHEMA = 1
    const val MAX_PROFILES = 5_000
    const val MAX_SUBSCRIPTIONS = 128
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_ID_CHARS = 128
    private const val MAX_CONNECTION_ID_CHARS = 256
    private const val MAX_NAME_CHARS = 120
    private const val MAX_RAW_CONFIG_CHARS = 16 * 1024
    private const val MAX_URL_CHARS = 4 * 1024
    private const val MAX_ETAG_CHARS = 512
    private const val MAX_ERROR_CHARS = 128
}
