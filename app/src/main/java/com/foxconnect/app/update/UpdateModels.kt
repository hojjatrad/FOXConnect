package com.foxconnect.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val apiDigest: String?,
)

internal data class AvailableUpdate(
    val versionCode: Int,
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val abi: String,
    val apk: ReleaseAsset,
    val checksum: ReleaseAsset,
)

internal sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data class Failure(val reason: UpdateFailure) : UpdateCheckResult
}

internal sealed interface UpdatePreparationResult {
    data class Ready(val update: AvailableUpdate, val apkPath: String) : UpdatePreparationResult
    data class Failure(val reason: UpdateFailure) : UpdatePreparationResult
}

internal enum class UpdateFailure {
    NETWORK,
    REPOSITORY_UNAVAILABLE,
    INVALID_METADATA,
    UNSUPPORTED_ABI,
    DOWNLOAD_TOO_LARGE,
    CHECKSUM_MISSING,
    CHECKSUM_MISMATCH,
    APK_INVALID,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SIGNATURE_MISMATCH,
    ABI_MISMATCH,
    STORAGE,
}

internal object UpdateReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val permittedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val hashPattern = Regex("^[0-9a-fA-F]{64}$")

    fun select(
        body: ByteArray,
        currentVersionCode: Int,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        assetChannel: String,
        officialRepository: String,
    ): UpdateCheckResult {
        if (officialRepository != OFFICIAL_REPOSITORY) {
            return UpdateCheckResult.Failure(UpdateFailure.INVALID_METADATA)
        }
        val supported = supportedAbis.filter { it in permittedAbis }.distinct()
        if (supported.isEmpty()) return UpdateCheckResult.Failure(UpdateFailure.UNSUPPORTED_ABI)
        if (assetChannel !in setOf("debug", "release")) {
            return UpdateCheckResult.Failure(UpdateFailure.INVALID_METADATA)
        }

        val releases = runCatching { json.parseToJsonElement(body.decodeToString()).jsonArray }
            .getOrElse { return UpdateCheckResult.Failure(UpdateFailure.INVALID_METADATA) }
        if (releases.size > MAX_RELEASES) {
            return UpdateCheckResult.Failure(UpdateFailure.INVALID_METADATA)
        }

        val candidates = releases.mapNotNull { element ->
            parseRelease(
                release = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null,
                currentVersionCode = currentVersionCode,
                supportedAbis = supported,
                includePrereleases = includePrereleases,
                assetChannel = assetChannel,
            )
        }
        return candidates.maxByOrNull { it.versionCode }
            ?.let(UpdateCheckResult::Available)
            ?: UpdateCheckResult.UpToDate
    }

    private fun parseRelease(
        release: JsonObject,
        currentVersionCode: Int,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        assetChannel: String,
    ): AvailableUpdate? {
        if (release.boolean("draft") != false) return null
        val prerelease = release.boolean("prerelease") ?: return null
        if (prerelease && !includePrereleases) return null
        val tag = release.string("tag_name")?.takeIf { it.length in 1..100 } ?: return null
        val releaseName = release.string("name").orEmpty().take(MAX_RELEASE_NAME_CHARS).ifBlank { tag }
        val notes = release.string("body").orEmpty().take(MAX_RELEASE_NOTES_CHARS)
        val publishedAt = release.string("published_at").orEmpty().take(64)
        val assets = release.array("assets")?.mapNotNull(::parseAsset) ?: return null
        if (assets.size > MAX_ASSETS_PER_RELEASE) return null

        val channelPart = if (assetChannel == "debug") "debug-" else ""
        val apkPattern = Regex("^FOXConnect-v([1-9][0-9]{0,9})-$channelPart(arm64-v8a|armeabi-v7a|x86_64|x86)\\.apk$")
        val indexed = assets.mapNotNull { asset ->
            val match = apkPattern.matchEntire(asset.name) ?: return@mapNotNull null
            val code = match.groupValues[1].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                ?: return@mapNotNull null
            val abi = match.groupValues[2]
            if (code <= currentVersionCode || abi !in supportedAbis) return@mapNotNull null
            Triple(asset, code, abi)
        }
        val selected = indexed.maxWithOrNull(
            compareBy<Triple<ReleaseAsset, Int, String>> { it.second }
                .thenByDescending { supportedAbis.indexOf(it.third) },
        ) ?: return null
        val checksum = assets.singleOrNull { it.name == "${selected.first.name}.sha256" } ?: return null
        if (checksum.size !in 1..MAX_CHECKSUM_BYTES) return null

        return AvailableUpdate(
            versionCode = selected.second,
            tagName = tag,
            releaseName = releaseName,
            releaseNotes = notes,
            publishedAt = publishedAt,
            prerelease = prerelease,
            abi = selected.third,
            apk = selected.first,
            checksum = checksum,
        )
    }

    private fun parseAsset(value: kotlinx.serialization.json.JsonElement): ReleaseAsset? {
        val asset = runCatching { value.jsonObject }.getOrNull() ?: return null
        val name = asset.string("name")?.takeIf { it.length in 1..160 } ?: return null
        val url = asset.string("browser_download_url")?.takeIf(::isOfficialAssetUrl) ?: return null
        val size = asset.long("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
        val digest = asset.string("digest")?.removePrefix("sha256:")?.lowercase()
            ?.takeIf { hashPattern.matches(it) }
        return ReleaseAsset(name, url, size, digest)
    }

    private fun isOfficialAssetUrl(raw: String): Boolean = runCatching {
        val uri = java.net.URI(raw)
        uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.path.startsWith("/$OFFICIAL_REPOSITORY/releases/download/")
    }.getOrDefault(false)

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        get(key)?.jsonPrimitive?.longOrNull

    private fun JsonObject.array(key: String): JsonArray? =
        runCatching { get(key)?.jsonArray }.getOrNull()

    const val OFFICIAL_REPOSITORY = "hojjatrad/FOXConnect"
    const val MAX_METADATA_BYTES = 512 * 1024L
    const val MAX_CHECKSUM_BYTES = 4 * 1024L
    const val MAX_APK_BYTES = 120 * 1024 * 1024L
    private const val MAX_RELEASES = 10
    private const val MAX_ASSETS_PER_RELEASE = 24
    private const val MAX_RELEASE_NAME_CHARS = 160
    private const val MAX_RELEASE_NOTES_CHARS = 4_000
}
