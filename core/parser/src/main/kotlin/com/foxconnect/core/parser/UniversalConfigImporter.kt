package com.foxconnect.core.parser

import com.foxconnect.core.model.ConnectableProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** One validated configuration discovered in text, Base64, gzip, or ZIP input. */
data class ImportedConfig(
    val rawConfig: String,
    val profile: ConnectableProfile,
    val entryName: String? = null,
)

data class ImportIssue(
    val code: String,
    val faMessage: String,
    val entryName: String? = null,
)

data class ConfigImportBatch(
    val configs: List<ImportedConfig>,
    val issues: List<ImportIssue>,
    val duplicateCount: Int,
)

/**
 * Defensive, bounded import pipeline shared by clipboard, share, file, QR, and
 * subscription paths. Only protocols with both a strict parser and engine mapping
 * become connectable; recognized future protocols are reported, never accepted.
 */
object UniversalConfigImporter {
    private const val MAX_INPUT_BYTES = 2 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 4 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 128
    private const val MAX_CONFIGS = 512
    private const val MAX_DECODE_DEPTH = 3
    private const val MAX_URI_CHARS = 16 * 1024

    private val uriPattern = Regex(
        pattern = "(?:vless|vmess|trojan|ss|hysteria2|hy2|hysteria|tuic|anytls|wg|wireguard|socks5|socks|https|http)://[^\\s\\\"'<>]+",
        option = RegexOption.IGNORE_CASE,
    )

    fun importText(raw: String): ConfigImportBatch {
        if (raw.length > MAX_INPUT_BYTES) {
            return ConfigImportBatch(
                configs = emptyList(),
                issues = listOf(ImportIssue("input_too_large", "حجم ورودی کانفیگ بیش از حد مجاز است")),
                duplicateCount = 0,
            )
        }
        return importBytes(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun importBytes(bytes: ByteArray, fileName: String? = null): ConfigImportBatch {
        val accumulator = Accumulator()
        if (bytes.isEmpty()) {
            accumulator.issue("empty", "ورودی کانفیگ خالی است", fileName)
        } else if (bytes.size > MAX_INPUT_BYTES) {
            accumulator.issue("input_too_large", "حجم ورودی کانفیگ بیش از حد مجاز است", fileName)
        } else {
            accumulator.processBytes(bytes, fileName, depth = 0)
        }
        return accumulator.result()
    }

    private class Accumulator {
        private val configs = linkedMapOf<String, ImportedConfig>()
        private val issues = mutableListOf<ImportIssue>()
        private var duplicates = 0
        private var expandedBytes = 0

        fun result() = ConfigImportBatch(configs.values.toList(), issues.toList(), duplicates)

        fun issue(code: String, message: String, entryName: String?) {
            if (issues.size < MAX_CONFIGS) issues += ImportIssue(code, message, entryName)
        }

        fun processBytes(bytes: ByteArray, entryName: String?, depth: Int) {
            if (depth > MAX_DECODE_DEPTH) {
                issue("nested_input", "لایه‌های فشرده یا رمزگذاری‌شده بیش از حد مجاز است", entryName)
                return
            }
            expandedBytes += bytes.size
            if (expandedBytes > MAX_EXPANDED_BYTES) {
                issue("expanded_too_large", "حجم دادهٔ بازشده بیش از حد مجاز است", entryName)
                return
            }
            when {
                bytes.hasPrefix(0x50, 0x4B, 0x03, 0x04) -> processZip(bytes, depth)
                bytes.hasPrefix(0x1F, 0x8B) -> processGzip(bytes, entryName, depth)
                else -> processText(bytes, entryName, depth)
            }
        }

        private fun processZip(bytes: ByteArray, depth: Int) {
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var count = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        count++
                        if (count > MAX_ARCHIVE_ENTRIES) {
                            issue("too_many_entries", "تعداد فایل‌های ZIP بیش از حد مجاز است", null)
                            break
                        }
                        val safeName = entry.name.replace('\\', '/').substringAfterLast('/').take(160)
                        val content = zip.readBounded(MAX_EXPANDED_BYTES - expandedBytes)
                        processBytes(content, safeName.ifBlank { null }, depth + 1)
                        zip.closeEntry()
                    }
                }
            }.onFailure {
                issue("invalid_zip", "فایل ZIP معتبر نیست یا بیش از حد بزرگ است", null)
            }
        }

        private fun processGzip(bytes: ByteArray, entryName: String?, depth: Int) {
            runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use {
                    processBytes(it.readBounded(MAX_EXPANDED_BYTES - expandedBytes), entryName, depth + 1)
                }
            }.onFailure {
                issue("invalid_gzip", "دادهٔ GZIP معتبر نیست یا بیش از حد بزرگ است", entryName)
            }
        }

        private fun processText(bytes: ByteArray, entryName: String?, depth: Int) {
            val text = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .removePrefix("\uFEFF")
                    .trim()
            }.getOrElse {
                issue("invalid_utf8", "متن فایل UTF-8 معتبر نیست", entryName)
                return
            }
            if (text.isBlank()) {
                issue("empty_entry", "فایل کانفیگ خالی است", entryName)
                return
            }
            // Some subscription panels wrap their Base64 value in a small JSON
            // envelope or escape newlines/slashes. Normalize only these transport
            // encodings; protocol credentials and query values are otherwise kept.
            val normalized = normalizeSubscriptionText(text)

            if (normalized.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?.equals("[Interface]", ignoreCase = true) == true
            ) {
                addUri(normalized, entryName)
                return
            }

            val candidates = uriPattern.findAll(normalized)
                .take(MAX_CONFIGS)
                .map { it.value.trimEnd(',', ';', ']', '}') }
                .filter { it.length <= MAX_URI_CHARS }
                .toList()
            if (candidates.isNotEmpty()) {
                candidates.forEach { addUri(it, entryName) }
                return
            }

            val compact = normalized.removeSurrounding("\"").filterNot(Char::isWhitespace)
            val encodedEnvelope = JSON_BASE64_FIELD.find(normalized)?.groupValues?.getOrNull(1)
                ?.filterNot(Char::isWhitespace)
            val decoded = sequenceOf(compact, encodedEnvelope)
                .filterNotNull()
                .mapNotNull(::decodeBase64)
                .firstOrNull { !it.contentEquals(bytes) }
            if (decoded != null && depth < MAX_DECODE_DEPTH) {
                processBytes(decoded, entryName, depth + 1)
            } else {
                issue("no_config", "هیچ لینک کانفیگ پشتیبانی‌شده‌ای پیدا نشد", entryName)
            }
        }

        private fun normalizeSubscriptionText(value: String): String = value
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\/", "/")
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")

        private fun addUri(raw: String, entryName: String?) {
            if (configs.size >= MAX_CONFIGS) {
                issue("too_many_configs", "تعداد کانفیگ‌ها بیش از حد مجاز است", entryName)
                return
            }
            when (val parsed = ConnectableProfileParser.parse(raw)) {
                is ParseResult.Error -> issue(parsed.code, parsed.faMessage, entryName)
                is ParseResult.Success -> {
                    val imported = ImportedConfig(raw, parsed.value, entryName)
                    val key = "${parsed.value.protocol.scheme}:${parsed.value.id}"
                    if (configs.putIfAbsent(key, imported) != null) duplicates++
                }
            }
        }

        private fun decodeBase64(value: String): ByteArray? {
            if (value.length !in 16..(MAX_INPUT_BYTES * 2) || !value.matches(BASE64_CHARS)) return null
            val padded = value + "=".repeat((4 - value.length % 4) % 4)
            return sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder())
                .mapNotNull { decoder -> runCatching { decoder.decode(padded) }.getOrNull() }
                .firstOrNull { it.isNotEmpty() && it.size <= MAX_EXPANDED_BYTES }
        }
    }

    private fun ByteArray.hasPrefix(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { this[it].toInt() and 0xFF == expected[it] }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        require(limit > 0) { "Expanded input limit exceeded" }
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Expanded input limit exceeded" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val BASE64_CHARS = Regex("[A-Za-z0-9_+/=-]+")
    private val JSON_BASE64_FIELD = Regex(
        "\"(?:data|content|subscription)\"\\s*:\\s*\"([A-Za-z0-9_+/=\\r\\n-]{16,})\"",
        RegexOption.IGNORE_CASE,
    )
}
