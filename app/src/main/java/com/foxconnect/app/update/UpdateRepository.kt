package com.foxconnect.app.update

import android.content.Context
import android.os.Build
import com.foxconnect.app.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File

internal class UpdateRepository(
    context: Context,
    private val client: StrictHttpsClient = StrictHttpsClient(),
) {
    private val applicationContext = context.applicationContext

    suspend fun check(includePrereleases: Boolean): UpdateCheckResult {
        return try {
            if (BuildConfig.GITHUB_REPOSITORY != UpdateReleaseParser.OFFICIAL_REPOSITORY) {
                return UpdateCheckResult.Failure(UpdateFailure.INVALID_METADATA)
            }
            val endpoint = "https://api.github.com/repos/${UpdateReleaseParser.OFFICIAL_REPOSITORY}/releases?per_page=10"
            val response = client.getMetadata(endpoint, UpdateReleaseParser.MAX_METADATA_BYTES)
            UpdateReleaseParser.select(
                body = response.body,
                currentVersionCode = BuildConfig.VERSION_CODE,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                includePrereleases = includePrereleases,
                assetChannel = BuildConfig.UPDATE_ASSET_CHANNEL,
                officialRepository = BuildConfig.GITHUB_REPOSITORY,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpProblem.Status) {
            UpdateCheckResult.Failure(
                if (error.code == 404) UpdateFailure.REPOSITORY_UNAVAILABLE else UpdateFailure.NETWORK,
            )
        } catch (_: HttpProblem.TooLarge) {
            UpdateCheckResult.Failure(UpdateFailure.DOWNLOAD_TOO_LARGE)
        } catch (_: Throwable) {
            UpdateCheckResult.Failure(UpdateFailure.NETWORK)
        }
    }

    suspend fun downloadAndVerify(update: AvailableUpdate): UpdatePreparationResult {
        val updateDirectory = File(applicationContext.cacheDir, UPDATE_DIRECTORY)
        return try {
            cleanUpdateDirectory(updateDirectory)
            val checksumResponse = client.getAssetText(
                update.checksum.downloadUrl,
                UpdateReleaseParser.MAX_CHECKSUM_BYTES,
            )
            val publishedHash = parsePublishedHash(checksumResponse.body, update.apk.name)
                ?: return UpdatePreparationResult.Failure(UpdateFailure.CHECKSUM_MISSING)
            if (update.apk.apiDigest != null && update.apk.apiDigest != publishedHash) {
                return UpdatePreparationResult.Failure(UpdateFailure.CHECKSUM_MISMATCH)
            }
            val destination = File(updateDirectory, update.apk.name)
            val downloaded = client.downloadAsset(
                url = update.apk.downloadUrl,
                destination = destination,
                maxBytes = UpdateReleaseParser.MAX_APK_BYTES,
                expectedBytes = update.apk.size,
            )
            if (downloaded.sha256 != publishedHash) {
                destination.delete()
                return UpdatePreparationResult.Failure(UpdateFailure.CHECKSUM_MISMATCH)
            }
            val apkFailure = ApkVerifier.verify(applicationContext, destination, update)
            if (apkFailure != null) {
                destination.delete()
                UpdatePreparationResult.Failure(apkFailure)
            } else {
                UpdatePreparationResult.Ready(update, destination.absolutePath)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpProblem.Status) {
            UpdatePreparationResult.Failure(
                if (error.code == 404) UpdateFailure.CHECKSUM_MISSING else UpdateFailure.NETWORK,
            )
        } catch (_: HttpProblem.TooLarge) {
            UpdatePreparationResult.Failure(UpdateFailure.DOWNLOAD_TOO_LARGE)
        } catch (_: HttpProblem.Storage) {
            UpdatePreparationResult.Failure(UpdateFailure.STORAGE)
        } catch (_: Throwable) {
            UpdatePreparationResult.Failure(UpdateFailure.NETWORK)
        }
    }

    fun clearPreparedUpdates() {
        cleanUpdateDirectory(File(applicationContext.cacheDir, UPDATE_DIRECTORY))
    }

    private fun parsePublishedHash(body: ByteArray, apkName: String): String? {
        if (body.isEmpty() || body.size > UpdateReleaseParser.MAX_CHECKSUM_BYTES) return null
        val matches = body.decodeToString().lineSequence().mapNotNull { line ->
            val match = CHECKSUM_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val filename = match.groupValues[2].removePrefix("*")
            if (filename == apkName) match.groupValues[1].lowercase() else null
        }.toList()
        return matches.singleOrNull()
    }

    private fun cleanUpdateDirectory(directory: File) {
        if (!directory.exists()) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                file.delete()
            }
        }
    }

    private companion object {
        const val UPDATE_DIRECTORY = "verified-updates"
        val CHECKSUM_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+(.+)$")
    }
}
