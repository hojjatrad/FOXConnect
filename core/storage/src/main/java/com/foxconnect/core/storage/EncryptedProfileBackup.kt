package com.foxconnect.core.storage

import com.foxconnect.core.model.ProfileVault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, passphrase-encrypted profile backup.
 *
 * This format deliberately does not use Android Keystore: a backup must be
 * restorable on another device. PBKDF2 parameters and the complete envelope
 * header are authenticated as AES-GCM additional data.
 */
object EncryptedProfileBackup {
    private const val MAGIC = 0x4643424B // FCBK
    private const val FORMAT_VERSION = 1
    private const val KDF_PBKDF2_HMAC_SHA256 = 1
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val HEADER_BYTES = 4 * 7 + SALT_BYTES + IV_BYTES
    private const val MAX_PASSPHRASE_CHARS = 1_024
    const val MIN_EXPORT_PASSPHRASE_CHARS = 12
    const val MAX_ENVELOPE_BYTES = ProfileVaultCodec.MAX_PLAINTEXT_BYTES + HEADER_BYTES + GCM_TAG_BYTES

    fun encrypt(vault: ProfileVault, passphrase: CharArray): ByteArray {
        require(passphrase.size in MIN_EXPORT_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            "Backup passphrase must contain at least $MIN_EXPORT_PASSPHRASE_CHARS characters"
        }
        val plaintext = ProfileVaultCodec.encode(vault)
        require(plaintext.size <= ProfileVaultCodec.MAX_PLAINTEXT_BYTES) { "Profile backup is too large" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val expectedCiphertextBytes = plaintext.size + GCM_TAG_BYTES
        val header = createHeader(salt, iv, expectedCiphertextBytes)
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        return try {
            val ciphertext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(header)
                doFinal(plaintext)
            }
            require(ciphertext.size == expectedCiphertextBytes) { "Unexpected backup ciphertext length" }
            header + ciphertext
        } finally {
            plaintext.fill(0)
            key.fill(0)
            salt.fill(0)
            iv.fill(0)
            header.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ProfileVault {
        require(passphrase.isNotEmpty() && passphrase.size <= MAX_PASSPHRASE_CHARS) {
            "Invalid backup passphrase length"
        }
        require(envelope.size in (HEADER_BYTES + GCM_TAG_BYTES)..MAX_ENVELOPE_BYTES) {
            "Encrypted profile backup has an invalid size"
        }
        val parsed = parseHeader(envelope)
        val key = deriveKey(passphrase, parsed.salt, parsed.iterations)
        val plaintext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, parsed.iv),
                )
                updateAAD(envelope, 0, parsed.headerBytes)
                doFinal(envelope, parsed.headerBytes, parsed.ciphertextBytes)
            }
        } finally {
            key.fill(0)
            parsed.salt.fill(0)
            parsed.iv.fill(0)
        }
        return try {
            ProfileVaultCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun createHeader(salt: ByteArray, iv: ByteArray, ciphertextBytes: Int): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(KDF_PBKDF2_HMAC_SHA256)
                output.writeInt(PBKDF2_ITERATIONS)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(iv.size)
                output.write(iv)
                output.writeInt(ciphertextBytes)
            }
            bytes.toByteArray().also { require(it.size == HEADER_BYTES) }
        }

    private fun parseHeader(envelope: ByteArray): ParsedHeader =
        DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            require(input.readInt() == MAGIC) { "Not a FOXConnect backup" }
            require(input.readInt() == FORMAT_VERSION) { "Unsupported backup version" }
            require(input.readInt() == KDF_PBKDF2_HMAC_SHA256) { "Unsupported backup KDF" }
            val iterations = input.readInt()
            require(iterations == PBKDF2_ITERATIONS) { "Unsupported backup KDF parameters" }
            val saltSize = input.readInt()
            require(saltSize == SALT_BYTES) { "Invalid backup salt" }
            val salt = ByteArray(saltSize).also(input::readFully)
            val ivSize = input.readInt()
            require(ivSize == IV_BYTES) { "Invalid backup IV" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val ciphertextBytes = input.readInt()
            require(ciphertextBytes in GCM_TAG_BYTES..ProfileVaultCodec.MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES) {
                "Invalid backup ciphertext length"
            }
            require(input.available() == ciphertextBytes) { "Truncated or trailing backup data" }
            ParsedHeader(iterations, salt, iv, HEADER_BYTES, ciphertextBytes)
        }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private data class ParsedHeader(
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val headerBytes: Int,
        val ciphertextBytes: Int,
    )

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
