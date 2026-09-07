package com.foxconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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

internal data class ProductSettings(
    val autoConnect: Boolean = false,
    val failoverEnabled: Boolean = true,
    val returnToPreferred: Boolean = false,
    val killSwitchEnabled: Boolean = true,
    val cooldownSeconds: Int = 60,
)

@Composable
internal fun SettingsDialog(
    initial: ProductSettings,
    onDismiss: () -> Unit,
    onSave: (ProductSettings) -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
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
                    title = stringResource(R.string.setting_return_preferred),
                    checked = value.returnToPreferred,
                    enabled = value.failoverEnabled,
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
