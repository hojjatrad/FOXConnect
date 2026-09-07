package com.foxconnect.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UniversalConfigImporterTest {
    private val first = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&sni=example.com#First"
    private val sameConnectionDifferentName =
        "vless://11111111-1111-4111-8111-111111111111@example.com:443?sni=example.com&security=tls#Renamed"
    private val second = "vless://22222222-2222-4222-8222-222222222222@vpn.example:8443?type=ws&path=%2Fws#Second"

    @Test
    fun importsMultipleAndDeduplicatesByConnectionIdentity() {
        val result = UniversalConfigImporter.importText("$first\n$sameConnectionDifferentName\n$second")

        assertEquals(2, result.configs.size)
        assertEquals(1, result.duplicateCount)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun unwrapsBase64Subscription() {
        val encoded = Base64.getEncoder().encodeToString("$first\n$second".toByteArray())
        val result = UniversalConfigImporter.importText(encoded)

        assertEquals(listOf("First", "Second"), result.configs.map { it.profile.name })
    }

    @Test
    fun unwrapsJsonBase64AndEscapedSubscriptionText() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("$first\n$second".toByteArray())
        val wrapped = "{\"data\":\"$encoded\"}"
        val escaped = first.replace("&", "&amp;") + "\\n" + second

        assertEquals(2, UniversalConfigImporter.importText(wrapped).configs.size)
        assertEquals(2, UniversalConfigImporter.importText(escaped).configs.size)
    }

    @Test
    fun importsSeventeenVlessProfilesFromGzipThenBase64SubscriptionShape() {
        val payload = (1..17).joinToString("\n") { index ->
            val id = java.util.UUID.nameUUIDFromBytes("sanitized-fixture-$index".toByteArray())
            "vless://$id@192.0.2.$index:443?security=tls&sni=example.invalid#Node-$index"
        }
        val base64 = Base64.getEncoder().encodeToString(payload.toByteArray())
        val gzip = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(base64.toByteArray()) }
        }.toByteArray()

        val result = UniversalConfigImporter.importBytes(gzip, "subscription-response")

        assertEquals(17, result.configs.size)
        assertEquals(0, result.duplicateCount)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.configs.all { it.profile is com.foxconnect.core.model.VlessProfile })
    }

    @Test
    fun unwrapsGzipAndZipWithinBounds() {
        val gzip = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(first.toByteArray()) }
        }.toByteArray()
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("nested/config.gz"))
                zip.write(gzip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("second.txt"))
                zip.write(second.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val result = UniversalConfigImporter.importBytes(archive, "profiles.zip")

        assertEquals(2, result.configs.size)
        assertEquals(setOf("config.gz", "second.txt"), result.configs.mapNotNull { it.entryName }.toSet())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun importsEnabledPhaseThreeProtocols() {
        val result = UniversalConfigImporter.importText(
            "trojan://secret@example.com:443#Trojan\n" +
                "ss://aes-256-gcm:secret@vpn.example:8388#Shadowsocks",
        )

        assertEquals(listOf("Trojan", "Shadowsocks"), result.configs.map { it.profile.name })
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun rejectsNonstandardWireGuardUri() {
        val result = UniversalConfigImporter.importText("wg://placeholder@example.com:51820#Unsupported")

        assertTrue(result.configs.isEmpty())
        assertEquals("wrong_format", result.issues.single().code)
    }

    @Test
    fun rejectsOversizedInputBeforeParsing() {
        val result = UniversalConfigImporter.importBytes(ByteArray(2 * 1024 * 1024 + 1) { 'A'.code.toByte() })

        assertEquals("input_too_large", result.issues.single().code)
    }
}
