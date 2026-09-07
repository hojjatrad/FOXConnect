package com.foxconnect.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseParserTest {
    @Test
    fun selectsNewestCompatibleStableAssetAndCompanionChecksum() {
        val result = UpdateReleaseParser.select(
            body = releases(
                release(version = 13, abi = "arm64-v8a", prerelease = false),
                release(version = 17, abi = "armeabi-v7a", prerelease = false),
                release(version = 14, abi = "arm64-v8a", prerelease = false),
            ),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = false,
            assetChannel = "release",
            officialRepository = UpdateReleaseParser.OFFICIAL_REPOSITORY,
        )

        assertTrue(result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).update
        assertEquals(14, update.versionCode)
        assertEquals("arm64-v8a", update.abi)
        assertEquals("FOXConnect-v14-arm64-v8a.apk.sha256", update.checksum.name)
    }

    @Test
    fun stablePolicyHidesPrereleases() {
        val hidden = UpdateReleaseParser.select(
            body = releases(release(13, "arm64-v8a", prerelease = true, channel = "debug")),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = false,
            assetChannel = "debug",
            officialRepository = UpdateReleaseParser.OFFICIAL_REPOSITORY,
        )
        val visible = UpdateReleaseParser.select(
            body = releases(release(13, "arm64-v8a", prerelease = true, channel = "debug")),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = true,
            assetChannel = "debug",
            officialRepository = UpdateReleaseParser.OFFICIAL_REPOSITORY,
        )

        assertEquals(UpdateCheckResult.UpToDate, hidden)
        assertTrue(visible is UpdateCheckResult.Available)
    }

    @Test
    fun rejectsDowngradeEqualVersionAndWrongChannel() {
        val result = UpdateReleaseParser.select(
            body = releases(
                release(12, "arm64-v8a", prerelease = false),
                release(99, "arm64-v8a", prerelease = false, channel = "debug"),
            ),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = true,
            assetChannel = "release",
            officialRepository = UpdateReleaseParser.OFFICIAL_REPOSITORY,
        )
        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun ignoresAssetOutsideFixedOfficialRepository() {
        val unsafe = release(13, "arm64-v8a", prerelease = false)
            .replace("https://github.com/hojjatrad/FOXConnect/releases/download/", "https://example.com/")
        val result = UpdateReleaseParser.select(
            body = releases(unsafe),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = true,
            assetChannel = "release",
            officialRepository = UpdateReleaseParser.OFFICIAL_REPOSITORY,
        )
        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun failsClosedForRepositoryMismatchAndMalformedJson() {
        val repositoryMismatch = UpdateReleaseParser.select(
            body = releases(release(13, "arm64-v8a", false)),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = true,
            assetChannel = "release",
            officialRepository = "someone/FOXConnect",
        )
        val malformed = UpdateReleaseParser.select(
            body = "not-json".encodeToByteArray(),
            currentVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = true,
            assetChannel = "release",
            officialRepository = UpdateReleaseParser.OFFICIAL_REPOSITORY,
        )

        assertEquals(UpdateFailure.INVALID_METADATA, (repositoryMismatch as UpdateCheckResult.Failure).reason)
        assertEquals(UpdateFailure.INVALID_METADATA, (malformed as UpdateCheckResult.Failure).reason)
    }

    private fun releases(vararg values: String): ByteArray =
        values.joinToString(prefix = "[", postfix = "]").encodeToByteArray()

    private fun release(
        version: Int,
        abi: String,
        prerelease: Boolean,
        channel: String = "release",
    ): String {
        val infix = if (channel == "debug") "debug-" else ""
        val apk = "FOXConnect-v$version-$infix$abi.apk"
        val base = "https://github.com/hojjatrad/FOXConnect/releases/download/v$version/"
        return """
            {
              "tag_name":"v$version",
              "name":"FOXConnect $version",
              "body":"Release notes",
              "published_at":"2026-09-07T00:00:00Z",
              "draft":false,
              "prerelease":$prerelease,
              "assets":[
                {"name":"$apk","browser_download_url":"$base$apk","size":1024,"digest":"sha256:${"a".repeat(64)}"},
                {"name":"$apk.sha256","browser_download_url":"$base$apk.sha256","size":100,"digest":"sha256:${"b".repeat(64)}"}
              ]
            }
        """.trimIndent()
    }
}
