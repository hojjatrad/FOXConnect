package com.foxconnect.core.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.foxconnect.core.model.ProtocolType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PendingEngineCandidate(
    val id: String,
    val profileName: String,
    val protocol: ProtocolType,
    val json: String,
)

internal data class PendingEngineConfig(
    val candidates: List<PendingEngineCandidate>,
    val activeIndex: Int = 0,
) {
    init {
        require(candidates.isNotEmpty() && candidates.size <= MAX_FAILOVER_CANDIDATES)
        require(activeIndex in candidates.indices)
        require(candidates.map { it.id }.toSet().size == candidates.size)
    }

    val active: PendingEngineCandidate get() = candidates[activeIndex]

    fun withActiveIndex(index: Int): PendingEngineConfig = copy(activeIndex = index)

    companion object {
        const val MAX_FAILOVER_CANDIDATES = 32

        fun single(profileName: String, protocol: ProtocolType, json: String): PendingEngineConfig =
            PendingEngineConfig(
                candidates = listOf(
                    PendingEngineCandidate(
                        id = "legacy-active",
                        profileName = profileName,
                        protocol = protocol,
                        json = json,
                    ),
                ),
            )
    }
}

/** Stores active and failover native configurations under a non-exportable Keystore key. */
internal class EngineConfigStore(context: Context) {
    private val directory = File(context.filesDir, "engine").apply { mkdirs() }
    private val encryptedFile = File(directory, "active.enc")

    @Synchronized
    fun write(config: PendingEngineConfig) {
        val plaintext = encode(config)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Engine configuration set is too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FILE_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
        val temp = File(directory, "active.enc.tmp")
        temp.outputStream().use { output ->
            output.write(envelope)
            output.fdSync()
        }
        check(temp.renameTo(encryptedFile) || run {
            temp.copyTo(encryptedFile, overwrite = true)
            temp.delete()
            true
        })
        deleteLegacyPlaintext()
    }

    @Synchronized
    fun clear() {
        encryptedFile.delete()
        File(directory, "active.enc.tmp").delete()
        deleteLegacyPlaintext()
    }

    @Synchronized
    fun read(): PendingEngineConfig? {
        deleteLegacyPlaintext()
        if (!encryptedFile.isFile) return null
        return runCatching {
            DataInputStream(encryptedFile.inputStream()).use { input ->
                require(input.readInt() == FILE_VERSION) { "Unsupported encrypted config version" }
                val ivLength = input.readInt()
                require(ivLength in 12..32) { "Invalid encrypted config IV" }
                val iv = ByteArray(ivLength).also(input::readFully)
                val ciphertextLength = input.readInt()
                require(ciphertextLength in 16..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted config length" }
                val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
                require(input.read() == -1) { "Trailing encrypted config data" }
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                decode(cipher.doFinal(ciphertext))
            }
        }.getOrNull()
    }

    private fun encode(config: PendingEngineConfig): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PAYLOAD_VERSION)
            output.writeInt(config.activeIndex)
            output.writeInt(config.candidates.size)
            config.candidates.forEach { candidate ->
                output.writeSizedUtf8(candidate.id)
                output.writeSizedUtf8(candidate.protocol.name)
                output.writeSizedUtf8(candidate.profileName.replace('\n', ' '))
                output.writeSizedUtf8(candidate.json)
            }
        }
        bytes.toByteArray()
    }

    private fun decode(plaintext: ByteArray): PendingEngineConfig =
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val version = input.readInt()
            val decoded = when (version) {
                LEGACY_PAYLOAD_VERSION -> {
                    val protocol = ProtocolType.valueOf(input.readSizedUtf8(MAX_FIELD_BYTES))
                    val name = input.readSizedUtf8(MAX_FIELD_BYTES).ifBlank { protocol.displayName }
                    val json = input.readSizedUtf8(MAX_CONFIG_BYTES)
                    PendingEngineConfig.single(name, protocol, json)
                }
                PAYLOAD_VERSION -> {
                    val activeIndex = input.readInt()
                    val count = input.readInt()
                    require(count in 1..PendingEngineConfig.MAX_FAILOVER_CANDIDATES) {
                        "Invalid failover candidate count"
                    }
                    val candidates = List(count) {
                        val id = input.readSizedUtf8(MAX_ID_BYTES)
                        require(id.isNotBlank()) { "Invalid failover candidate ID" }
                        val protocol = ProtocolType.valueOf(input.readSizedUtf8(MAX_FIELD_BYTES))
                        val name = input.readSizedUtf8(MAX_FIELD_BYTES).ifBlank { protocol.displayName }
                        val json = input.readSizedUtf8(MAX_CONFIG_BYTES)
                        PendingEngineCandidate(id, name, protocol, json)
                    }
                    PendingEngineConfig(candidates, activeIndex)
                }
                else -> error("Unsupported config payload version")
            }
            require(input.read() == -1) { "Trailing config payload data" }
            decoded
        }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun deleteLegacyPlaintext() {
        File(directory, "active.json").delete()
        File(directory, "active.meta").delete()
        File(directory, "active.json.tmp").delete()
    }

    private fun java.io.FileOutputStream.fdSync() = fd.sync()

    private fun DataOutputStream.writeSizedUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSizedUtf8(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..maxBytes) { "Invalid UTF-8 field length" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "foxconnect_active_config_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FILE_VERSION = 1
        const val LEGACY_PAYLOAD_VERSION = 1
        const val PAYLOAD_VERSION = 2
        const val MAX_ID_BYTES = 512
        const val MAX_FIELD_BYTES = 16 * 1024
        const val MAX_CONFIG_BYTES = 512 * 1024
        const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 64
    }
}
