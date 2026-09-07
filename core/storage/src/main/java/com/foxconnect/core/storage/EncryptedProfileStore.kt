package com.foxconnect.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.foxconnect.core.model.ProfileVault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface VaultReadResult {
    data class Success(val vault: ProfileVault) : VaultReadResult
    data class Failure(val errorCode: String, val cause: Throwable) : VaultReadResult
}

interface ProfileVaultStore {
    fun read(): VaultReadResult
    fun write(vault: ProfileVault)
}

/** Authenticated profile-vault storage backed by a non-exportable Keystore key. */
class EncryptedProfileStore(context: Context) : ProfileVaultStore {
    private val directory = context.filesDir.resolve("profiles").apply { mkdirs() }
    private val atomicFile = AtomicFile(directory.resolve("vault.enc"))

    @Synchronized
    override fun read(): VaultReadResult {
        if (!atomicFile.baseFile.isFile) return VaultReadResult.Success(ProfileVault())
        return runCatching {
            require(atomicFile.baseFile.length() <= MAX_ENVELOPE_BYTES) { "Encrypted profile vault is too large" }
            val envelope = atomicFile.readFully()
            require(envelope.size <= MAX_ENVELOPE_BYTES) { "Encrypted profile vault is too large" }
            val plaintext = decrypt(envelope)
            VaultReadResult.Success(ProfileVaultCodec.decode(plaintext))
        }.getOrElse { VaultReadResult.Failure("vault_read_failed", it) }
    }

    @Synchronized
    override fun write(vault: ProfileVault) {
        val plaintext = ProfileVaultCodec.encode(vault)
        require(plaintext.size <= ProfileVaultCodec.MAX_PLAINTEXT_BYTES) { "Profile vault is too large" }
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

        val output = atomicFile.startWrite()
        try {
            output.write(envelope)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun decrypt(envelope: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(envelope)).use { input ->
        require(input.readInt() == FILE_VERSION) { "Unsupported encrypted profile vault version" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid profile vault IV" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 16..MAX_CIPHERTEXT_BYTES) { "Invalid profile vault length" }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        require(input.read() == -1) { "Trailing profile vault data" }
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ciphertext)
        }
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

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "foxconnect_profile_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FILE_VERSION = 1
        const val MAX_CIPHERTEXT_BYTES = ProfileVaultCodec.MAX_PLAINTEXT_BYTES + 64
        const val MAX_ENVELOPE_BYTES = MAX_CIPHERTEXT_BYTES + 64
    }
}
