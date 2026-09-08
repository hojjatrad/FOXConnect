package com.foxconnect.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProfileSource {
    SHARE,
    CLIPBOARD,
    FILE,
    QR,
    SUBSCRIPTION,
    PANEL,
    MANUAL,
}

/**
 * Encrypted-at-rest profile record. [rawConfig] may contain credentials and must
 * never be written to logs, analytics, notifications, or unencrypted storage.
 */
@Serializable
data class ManagedProfile(
    val id: String,
    val connectionId: String,
    val name: String,
    val protocol: ProtocolType,
    val rawConfig: String,
    val source: ProfileSource,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val subscriptionId: String? = null,
    val favorite: Boolean = false,
)

@Serializable
data class SubscriptionRecord(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdatedAtEpochMs: Long? = null,
    val lastError: String? = null,
    val etag: String? = null,
)

@Serializable
data class ProfileVault(
    val schemaVersion: Int = 1,
    val profiles: List<ManagedProfile> = emptyList(),
    val subscriptions: List<SubscriptionRecord> = emptyList(),
    val selectedProfileId: String? = null,
)
