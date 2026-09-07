package com.foxconnect.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.foxconnect.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object ApkVerifier {
    fun verify(context: Context, file: File, update: AvailableUpdate): UpdateFailure? {
        if (!file.isFile || file.length() != update.apk.size) return UpdateFailure.APK_INVALID
        if (update.versionCode <= BuildConfig.VERSION_CODE) return UpdateFailure.VERSION_MISMATCH
        if (update.abi !in Build.SUPPORTED_ABIS) return UpdateFailure.ABI_MISMATCH

        val archive = archivePackageInfo(context.packageManager, file) ?: return UpdateFailure.APK_INVALID
        if (archive.packageName != BuildConfig.APPLICATION_ID) return UpdateFailure.PACKAGE_MISMATCH
        if (
            PackageInfoCompat.getLongVersionCode(archive) != update.versionCode.toLong() ||
            PackageInfoCompat.getLongVersionCode(archive) <= BuildConfig.VERSION_CODE.toLong()
        ) {
            return UpdateFailure.VERSION_MISMATCH
        }

        val installed = installedPackageInfo(context.packageManager) ?: return UpdateFailure.APK_INVALID
        val archiveSigners = currentSignerDigests(archive) ?: return UpdateFailure.SIGNATURE_MISMATCH
        val installedSigners = currentSignerDigests(installed) ?: return UpdateFailure.SIGNATURE_MISMATCH
        if (archiveSigners != installedSigners) return UpdateFailure.SIGNATURE_MISMATCH

        val packagedAbis = runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.count { char -> char == '/' } >= 2 }
                    .map { it.substringAfter("lib/").substringBefore('/') }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        }.getOrElse { return UpdateFailure.APK_INVALID }
        if (packagedAbis != setOf(update.abi)) return UpdateFailure.ABI_MISMATCH
        return null
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(packageManager: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(packageManager: PackageManager): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    BuildConfig.APPLICATION_ID,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return null
        if (signatures.isEmpty()) return null
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.toSet()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
