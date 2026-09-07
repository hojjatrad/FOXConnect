package com.foxconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxconnect.app.BuildConfig
import com.foxconnect.app.R
import com.foxconnect.app.update.UpdateFailure
import com.foxconnect.app.update.UpdateUiState

internal data class ProductSettings(
    val autoConnect: Boolean = false,
    val failoverEnabled: Boolean = true,
    val qualitySwitchEnabled: Boolean = true,
    val weakLatencyThresholdMs: Int = 1_500,
    val returnToPreferred: Boolean = false,
    val killSwitchEnabled: Boolean = true,
    val cooldownSeconds: Int = 60,
    val periodicUpdateChecks: Boolean = true,
    val includePrereleases: Boolean = BuildConfig.UPDATE_ASSET_CHANNEL == "debug",
)

@Composable
internal fun SettingsDialog(
    initial: ProductSettings,
    updateState: UpdateUiState,
    onDismiss: () -> Unit,
    onSave: (ProductSettings) -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onCheckForUpdates: (includePrereleases: Boolean) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val updateBusy = updateState is UpdateUiState.Checking || updateState is UpdateUiState.Downloading
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingSwitch(
                    title = stringResource(R.string.setting_auto_connect),
                    checked = value.autoConnect,
                    onCheckedChange = { value = value.copy(autoConnect = it) },
                )
                SettingSwitch(
                    title = stringResource(R.string.setting_failover),
                    checked = value.failoverEnabled,
                    onCheckedChange = { value = value.copy(failoverEnabled = it) },
                )
                SettingSwitch(
                    title = stringResource(R.string.setting_quality_switch),
                    checked = value.qualitySwitchEnabled,
                    enabled = value.failoverEnabled,
                    onCheckedChange = {
                        value = value.copy(
                            qualitySwitchEnabled = it,
                            returnToPreferred = if (it) false else value.returnToPreferred,
                        )
                    },
                )
                Text(stringResource(R.string.setting_weak_latency))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(800, 1_500, 2_500).forEach { latencyMs ->
                        FilterChip(
                            selected = value.weakLatencyThresholdMs == latencyMs,
                            enabled = value.failoverEnabled && value.qualitySwitchEnabled,
                            onClick = { value = value.copy(weakLatencyThresholdMs = latencyMs) },
                            label = { Text(stringResource(R.string.milliseconds_short, latencyMs)) },
                        )
                    }
                }
                SettingSwitch(
                    title = stringResource(R.string.setting_return_preferred),
                    checked = value.returnToPreferred,
                    enabled = value.failoverEnabled && !value.qualitySwitchEnabled,
                    onCheckedChange = { value = value.copy(returnToPreferred = it) },
                )
                SettingSwitch(
                    title = stringResource(R.string.setting_kill_switch),
                    checked = value.killSwitchEnabled,
                    onCheckedChange = { value = value.copy(killSwitchEnabled = it) },
                )
                Text(stringResource(R.string.setting_cooldown))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120).forEach { seconds ->
                        FilterChip(
                            selected = value.cooldownSeconds == seconds,
                            onClick = { value = value.copy(cooldownSeconds = seconds) },
                            label = { Text(stringResource(R.string.seconds_short, seconds)) },
                        )
                    }
                }
                Text(stringResource(R.string.kill_switch_system_note))
                TextButton(onClick = onOpenSystemVpnSettings) {
                    Text(stringResource(R.string.open_system_vpn_settings))
                }

                HorizontalDivider()
                Text(stringResource(R.string.update_section_title))
                SettingSwitch(
                    title = stringResource(R.string.update_periodic_checks),
                    checked = value.periodicUpdateChecks,
                    onCheckedChange = { value = value.copy(periodicUpdateChecks = it) },
                )
                SettingSwitch(
                    title = stringResource(R.string.update_include_prereleases),
                    checked = value.includePrereleases,
                    onCheckedChange = { value = value.copy(includePrereleases = it) },
                )
                Text(stringResource(R.string.update_privacy_note))
                UpdateStatus(
                    state = updateState,
                    onDownloadUpdate = onDownloadUpdate,
                    onInstallUpdate = onInstallUpdate,
                )
                TextButton(
                    enabled = !updateBusy,
                    onClick = { onCheckForUpdates(value.includePrereleases) },
                ) {
                    Text(stringResource(R.string.update_check_now))
                }
                Text(
                    stringResource(
                        R.string.diagnostic_build_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun UpdateStatus(
    state: UpdateUiState,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    when (state) {
        UpdateUiState.Idle -> Text(stringResource(R.string.update_not_checked))
        UpdateUiState.Checking -> Text(stringResource(R.string.update_checking))
        UpdateUiState.UpToDate -> Text(stringResource(R.string.update_up_to_date))
        is UpdateUiState.Available -> {
            UpdateInformation(state.update)
            TextButton(onClick = onDownloadUpdate) {
                Text(stringResource(R.string.update_download_and_verify))
            }
        }
        is UpdateUiState.Downloading -> {
            UpdateInformation(state.update)
            Text(stringResource(R.string.update_downloading))
        }
        is UpdateUiState.Ready -> {
            UpdateInformation(state.update)
            Text(stringResource(R.string.update_verified_ready))
            TextButton(onClick = onInstallUpdate) {
                Text(stringResource(R.string.update_install))
            }
        }
        is UpdateUiState.Failed -> Text(updateFailureText(state.reason))
    }
}

@Composable
private fun UpdateInformation(update: com.foxconnect.app.update.AvailableUpdate) {
    Text(
        stringResource(
            R.string.update_available_version,
            update.releaseName,
            update.versionCode,
            update.abi,
        ),
    )
    if (update.prerelease) Text(stringResource(R.string.update_prerelease_warning))
    if (update.publishedAt.isNotBlank()) {
        Text(stringResource(R.string.update_published_at, update.publishedAt))
    }
    if (update.releaseNotes.isNotBlank()) Text(update.releaseNotes)
}

@Composable
private fun updateFailureText(reason: UpdateFailure): String = stringResource(
    when (reason) {
        UpdateFailure.NETWORK -> R.string.update_error_network
        UpdateFailure.REPOSITORY_UNAVAILABLE -> R.string.update_error_repository
        UpdateFailure.INVALID_METADATA -> R.string.update_error_metadata
        UpdateFailure.UNSUPPORTED_ABI -> R.string.update_error_abi
        UpdateFailure.DOWNLOAD_TOO_LARGE -> R.string.update_error_size
        UpdateFailure.CHECKSUM_MISSING -> R.string.update_error_checksum_missing
        UpdateFailure.CHECKSUM_MISMATCH -> R.string.update_error_checksum
        UpdateFailure.APK_INVALID -> R.string.update_error_apk
        UpdateFailure.PACKAGE_MISMATCH -> R.string.update_error_package
        UpdateFailure.VERSION_MISMATCH -> R.string.update_error_version
        UpdateFailure.SIGNATURE_MISMATCH -> R.string.update_error_signature
        UpdateFailure.ABI_MISMATCH -> R.string.update_error_abi
        UpdateFailure.STORAGE -> R.string.update_error_storage
    },
)

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
