package com.foxconnect.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.foxconnect.app.BuildConfig
import java.io.File

internal object UpdateInstaller {
    fun handOff(context: Context, apkPath: String): InstallHandoff {
        val file = File(apkPath)
        val updateRoot = File(context.cacheDir, "verified-updates")
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return InstallHandoff.Failed
        val canonicalRoot = runCatching { updateRoot.canonicalFile }.getOrNull() ?: return InstallHandoff.Failed
        if (!canonicalFile.isFile || canonicalFile.parentFile != canonicalRoot) return InstallHandoff.Failed

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
            )
            return runCatching {
                context.startActivity(settingsIntent)
                InstallHandoff.PermissionRequired
            }.getOrDefault(InstallHandoff.Failed)
        }

        val contentUri = runCatching {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.updates", canonicalFile)
        }.getOrNull() ?: return InstallHandoff.Failed
        val installer = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(installer)
            InstallHandoff.Started
        }.getOrDefault(InstallHandoff.Failed)
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}

internal enum class InstallHandoff { Started, PermissionRequired, Failed }
