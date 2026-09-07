package com.foxconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxconnect.app.R
import com.foxconnect.core.engine.TunnelEvent
import com.foxconnect.core.engine.TunnelEventCode
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsScreen(
    entries: List<TunnelEvent>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                        Text(stringResource(R.string.clear_logs))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanvasColor,
                    titleContentColor = InkColor,
                ),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.logs_empty), color = MutedColor)
                Text(stringResource(R.string.logs_privacy_note), color = MutedColor, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.logs_privacy_note),
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                itemsIndexed(
                    entries.asReversed(),
                    key = { index, entry -> "${entry.timestampEpochMs}:${entry.code}:$index" },
                ) { _, entry ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                                .format(Date(entry.timestampEpochMs)),
                            color = MutedColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.42f),
                        )
                        Text(
                            eventText(entry.code),
                            color = InkColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(0.58f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun eventText(code: TunnelEventCode): String = stringResource(
    when (code) {
        TunnelEventCode.CONNECT_REQUESTED -> R.string.log_connect_requested
        TunnelEventCode.CONNECTING -> R.string.log_connecting
        TunnelEventCode.VERIFIED -> R.string.log_verified
        TunnelEventCode.HEALTH_FAILED -> R.string.log_health_failed
        TunnelEventCode.QUALITY_DEGRADED -> R.string.log_quality_degraded
        TunnelEventCode.TUNNEL_VERIFICATION_FAILED -> R.string.log_tunnel_verification_failed
        TunnelEventCode.CORE_SETUP_FAILED -> R.string.log_core_setup_failed
        TunnelEventCode.CORE_VERSION_FAILED -> R.string.log_core_version_failed
        TunnelEventCode.CORE_CONFIG_REJECTED -> R.string.log_core_config_rejected
        TunnelEventCode.CORE_COMMAND_FAILED -> R.string.log_core_command_failed
        TunnelEventCode.CORE_NETWORK_MONITOR_FAILED -> R.string.log_core_network_monitor_failed
        TunnelEventCode.CORE_SERVICE_START_FAILED -> R.string.log_core_service_start_failed
        TunnelEventCode.CORE_POST_START_FAILED -> R.string.log_core_post_start_failed
        TunnelEventCode.TUN_ESTABLISH_FAILED -> R.string.log_tun_establish_failed
        TunnelEventCode.SOCKET_PROTECTION_FAILED -> R.string.log_socket_protection_failed
        TunnelEventCode.CORE_START_FAILED -> R.string.log_core_start_failed
        TunnelEventCode.SWITCHING -> R.string.log_switching
        TunnelEventCode.ALL_PROFILES_FAILED -> R.string.log_all_failed
        TunnelEventCode.KILL_SWITCH_BLOCKING -> R.string.log_kill_switch
        TunnelEventCode.DISCONNECT_REQUESTED -> R.string.log_disconnect_requested
        TunnelEventCode.DISCONNECTED -> R.string.log_disconnected
        TunnelEventCode.PERMISSION_REVOKED -> R.string.log_permission_revoked
        TunnelEventCode.CORE_PROCESS_STOPPED -> R.string.log_core_process_stopped
        TunnelEventCode.UI_PROCESS_CRASHED -> R.string.log_ui_process_crashed
    },
)
