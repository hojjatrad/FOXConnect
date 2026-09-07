package com.foxconnect.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foxconnect.app.R
import com.foxconnect.core.engine.ProfileHealthMetric
import com.foxconnect.core.model.ManagedProfile
import com.foxconnect.core.model.ProtocolType
import com.foxconnect.core.model.TransportType
import com.foxconnect.core.model.VlessProfile
import com.foxconnect.core.parser.ParseResult
import com.foxconnect.core.parser.VlessUriFormatter
import com.foxconnect.core.parser.VlessUriParser
import com.foxconnect.core.storage.ProfileRepositoryState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    state: ProfileRepositoryState,
    healthMetrics: Map<String, ProfileHealthMetric>,
    pingRunning: Boolean,
    failoverEnabled: Boolean,
    onBack: () -> Unit,
    onPingAll: () -> Unit,
    onRefreshAllSubscriptions: () -> Unit,
    onSelect: (ManagedProfile) -> Unit,
    onClipboard: () -> Unit,
    onFile: () -> Unit,
    onQr: () -> Unit,
    onManual: () -> Unit,
    onSubscription: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onEdit: (ManagedProfile) -> Unit,
    onDuplicate: (ManagedProfile) -> Unit,
    onDelete: (ManagedProfile) -> Unit,
    onFavorite: (ManagedProfile, Boolean) -> Unit,
    onRefreshSubscription: (com.foxconnect.core.model.SubscriptionRecord) -> Unit,
    onSetSubscriptionEnabled: (String, Boolean) -> Unit,
    onDeleteSubscription: (String) -> Unit,
) {
    var sortByLatency by remember { mutableStateOf(false) }
    val visibleProfiles = remember(state.profiles, healthMetrics, sortByLatency) {
        if (!sortByLatency) {
            state.profiles
        } else {
            state.profiles.sortedWith(
                compareByDescending<ManagedProfile> { it.favorite }
                    .thenBy { healthMetrics[it.id]?.latencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.name.lowercase() },
            )
        }
    }
    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanvasColor,
                    titleContentColor = InkColor,
                    navigationIconContentColor = InkColor,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ImportActions(
                    onClipboard,
                    onFile,
                    onQr,
                    onManual,
                    onSubscription,
                    onExportBackup,
                    onRestoreBackup,
                )
            }
            item {
                ConnectionTools(
                    profileCount = state.profiles.size.coerceAtMost(32),
                    subscriptionCount = state.subscriptions.count { it.enabled },
                    pingRunning = pingRunning,
                    failoverEnabled = failoverEnabled,
                    onPingAll = onPingAll,
                    onRefreshAllSubscriptions = onRefreshAllSubscriptions,
                )
            }
            if (state.loaded && state.errorCode == null && state.profiles.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilterChip(
                            selected = sortByLatency,
                            onClick = { sortByLatency = !sortByLatency },
                            label = { Text(stringResource(R.string.sort_by_latency)) },
                        )
                    }
                }
            }
            when {
                !state.loaded -> item { Message(stringResource(R.string.loading)) }
                state.errorCode != null -> item {
                    Message(stringResource(R.string.profiles_storage_error), ErrorColor)
                }
                state.profiles.isEmpty() -> item { Message(stringResource(R.string.profiles_empty)) }
                else -> items(visibleProfiles, key = ManagedProfile::id) { profile ->
                    ProfileRow(
                        profile = profile,
                        health = healthMetrics[profile.id],
                        selected = profile.id == state.selectedProfileId,
                        onSelect = { onSelect(profile) },
                        onEdit = { onEdit(profile) },
                        onDuplicate = { onDuplicate(profile) },
                        onDelete = { onDelete(profile) },
                        onFavorite = { onFavorite(profile, !profile.favorite) },
                    )
                }
            }
            if (state.subscriptions.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.subscriptions),
                        style = MaterialTheme.typography.titleLarge,
                        color = InkColor,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                }
                items(state.subscriptions, key = { it.id }) { subscription ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(subscription.name, color = InkColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    runCatching {
                                        java.net.URI(subscription.url).host?.let { "https://$it" } ?: "https://…"
                                    }.getOrDefault("https://…"),
                                    color = MutedColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subscription.lastError != null) {
                                    Text(
                                        stringResource(R.string.subscription_last_refresh_failed),
                                        color = ErrorColor,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Switch(
                                checked = subscription.enabled,
                                onCheckedChange = { onSetSubscriptionEnabled(subscription.id, it) },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.subscription_auto_refresh),
                                color = MutedColor,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRefreshSubscription(subscription) }) {
                                Text(stringResource(R.string.refresh))
                            }
                            TextButton(onClick = { onDeleteSubscription(subscription.id) }) {
                                Text(stringResource(R.string.profile_delete), color = ErrorColor)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectionTools(
    profileCount: Int,
    subscriptionCount: Int,
    pingRunning: Boolean,
    failoverEnabled: Boolean,
    onPingAll: () -> Unit,
    onRefreshAllSubscriptions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(
                if (failoverEnabled) R.string.failover_ready else R.string.failover_disabled,
                profileCount,
            ),
            color = if (failoverEnabled) ConnectedColor else ErrorColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPingAll,
                enabled = profileCount > 0 && !pingRunning,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (pingRunning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(if (pingRunning) R.string.ping_running else R.string.ping_all), maxLines = 1)
            }
            Button(
                onClick = onRefreshAllSubscriptions,
                enabled = subscriptionCount > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.refresh_all_subscriptions), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text(
            stringResource(R.string.endpoint_ping_note),
            color = MutedColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ImportActions(
    onClipboard: () -> Unit,
    onFile: () -> Unit,
    onQr: () -> Unit,
    onManual: () -> Unit,
    onSubscription: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.import_title), color = MutedColor, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportButton(R.string.import_clipboard, onClipboard, Modifier.weight(1f))
            ImportButton(R.string.import_file, onFile, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportButton(R.string.import_manual, onManual, Modifier.weight(1f))
            ImportButton(R.string.import_qr, onQr, Modifier.weight(1f))
        }
        ImportButton(R.string.import_subscription, onSubscription, Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.backup_title),
            color = MutedColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportButton(R.string.backup_export, onExportBackup, Modifier.weight(1f))
            ImportButton(R.string.backup_restore, onRestoreBackup, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ImportButton(label: Int, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color = MutedColor) {
    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color)
    }
}

@Composable
private fun ProfileRow(
    profile: ManagedProfile,
    health: ProfileHealthMetric?,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val cooldownUntil = health?.cooldownUntilEpochMs
    val latency = health?.latencyMs
    val healthText = when {
        cooldownUntil != null && cooldownUntil > now -> {
            val seconds = ((cooldownUntil - now + 999) / 1_000)
            stringResource(R.string.profile_cooldown, seconds)
        }
        health?.endpointProbeFailed == true -> stringResource(R.string.endpoint_ping_failed)
        latency != null -> stringResource(R.string.profile_latency, latency)
        profile.protocol in setOf(
            ProtocolType.HYSTERIA,
            ProtocolType.HYSTERIA2,
            ProtocolType.TUIC,
            ProtocolType.WIREGUARD,
        ) -> stringResource(R.string.endpoint_ping_unsupported)
        else -> stringResource(R.string.profile_not_measured)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) PrimaryIdleColor else DividerColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, if (selected) ConnectedColor else DividerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Surface(Modifier.size(8.dp), shape = CircleShape, color = ConnectedColor) {}
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                color = InkColor,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${profile.protocol.displayName} · $healthText",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (profile.favorite) Icon(Icons.Default.Star, null, tint = PrimaryIdleColor)
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, null, tint = MutedColor) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (profile.favorite) R.string.profile_unfavorite else R.string.profile_favorite)) },
                    leadingIcon = {
                        Icon(Icons.Default.Star, null)
                    },
                    onClick = { menuOpen = false; onFavorite() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_edit)) },
                    enabled = profile.protocol == ProtocolType.VLESS,
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_duplicate)) },
                    onClick = { menuOpen = false; onDuplicate() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_delete), color = ErrorColor) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
fun VlessEditorDialog(
    managedProfile: ManagedProfile?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val existing = remember(managedProfile?.id, managedProfile?.rawConfig) {
        managedProfile?.rawConfig?.let(::parseVless)
    }
    var name by remember(managedProfile?.id) { mutableStateOf(managedProfile?.name ?: "") }
    var host by remember(managedProfile?.id) { mutableStateOf(existing?.host ?: "") }
    var port by remember(managedProfile?.id) { mutableStateOf(existing?.port?.toString() ?: "443") }
    var uuid by remember(managedProfile?.id) { mutableStateOf(existing?.uuid ?: "") }
    var flow by remember(managedProfile?.id) { mutableStateOf(existing?.flow ?: "") }
    var tls by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.enabled ?: true) }
    var reality by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.realityEnabled ?: false) }
    var serverName by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.serverName ?: "") }
    var fingerprint by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.fingerprint ?: "chrome") }
    var alpn by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.alpn?.joinToString(",") ?: "") }
    var publicKey by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.realityPublicKey ?: "") }
    var shortId by remember(managedProfile?.id) { mutableStateOf(existing?.tls?.realityShortId ?: "") }
    var transport by remember(managedProfile?.id) { mutableStateOf(existing?.transport?.type ?: TransportType.TCP) }
    var path by remember(managedProfile?.id) { mutableStateOf(existing?.transport?.path ?: "") }
    var transportHost by remember(managedProfile?.id) { mutableStateOf(existing?.transport?.host ?: "") }
    var serviceName by remember(managedProfile?.id) { mutableStateOf(existing?.transport?.serviceName ?: "") }
    var authority by remember(managedProfile?.id) { mutableStateOf(existing?.transport?.authority ?: "") }
    var invalid by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceColor,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(if (managedProfile == null) R.string.new_vless else R.string.edit_vless),
                    style = MaterialTheme.typography.titleLarge,
                    color = InkColor,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EditorField(name, { name = it }, R.string.name)
                    EditorField(host, { host = it }, R.string.server_address)
                    EditorField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        R.string.server_port,
                        KeyboardType.Number,
                    )
                    EditorField(uuid, { uuid = it.trim() }, R.string.uuid)
                    EditorField(flow, { flow = it }, R.string.flow)
                    ToggleRow(R.string.enable_tls, tls) { tls = it; if (!it) reality = false }
                    ToggleRow(R.string.enable_reality, reality) { reality = it; if (it) tls = true }
                    if (tls) {
                        EditorField(serverName, { serverName = it }, R.string.server_name)
                        EditorField(fingerprint, { fingerprint = it }, R.string.fingerprint)
                        EditorField(alpn, { alpn = it }, R.string.alpn)
                    }
                    if (reality) {
                        EditorField(publicKey, { publicKey = it }, R.string.reality_public_key)
                        EditorField(shortId, { shortId = it }, R.string.reality_short_id)
                    }
                    Text(stringResource(R.string.transport), color = MutedColor)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TransportType.TCP,
                            TransportType.WEBSOCKET,
                            TransportType.GRPC,
                            TransportType.HTTP_UPGRADE,
                            TransportType.SPLIT_HTTP,
                        ).forEach { item ->
                            FilterChip(
                                selected = transport == item,
                                onClick = { transport = item },
                                label = { Text(item.wireName) },
                            )
                        }
                    }
                    if (transport in setOf(TransportType.WEBSOCKET, TransportType.HTTP_UPGRADE, TransportType.SPLIT_HTTP)) {
                        EditorField(path, { path = it }, R.string.transport_path)
                        EditorField(transportHost, { transportHost = it }, R.string.transport_host)
                    }
                    if (transport == TransportType.GRPC) {
                        EditorField(serviceName, { serviceName = it }, R.string.service_name)
                        EditorField(authority, { authority = it }, R.string.authority)
                    }
                    if (invalid) Text(stringResource(R.string.invalid_form), color = ErrorColor)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        val raw = runCatching {
                            require(name.isNotBlank() && host.isNotBlank() && uuid.isNotBlank())
                            if (reality) require(publicKey.isNotBlank())
                            val base = existing ?: VlessProfile(
                                id = "",
                                name = name.trim(),
                                host = host.trim(),
                                port = port.toInt(),
                                uuid = uuid.trim(),
                            )
                            val candidate = base.copy(
                                name = name.trim(),
                                host = host.trim().removeSurrounding("[", "]"),
                                port = port.toInt(),
                                uuid = uuid.trim(),
                                flow = flow.trim().ifBlank { null },
                                tls = base.tls.copy(
                                    enabled = tls,
                                    realityEnabled = reality,
                                    serverName = serverName.trim().ifBlank { null },
                                    fingerprint = fingerprint.trim().ifBlank { null },
                                    realityPublicKey = publicKey.trim().ifBlank { null },
                                    realityShortId = shortId.trim().ifBlank { null },
                                    alpn = alpn.split(',').map(String::trim).filter(String::isNotEmpty),
                                ),
                                transport = base.transport.copy(
                                    type = transport,
                                    path = path.trim().takeIf {
                                        it.isNotBlank() && transport in setOf(
                                            TransportType.WEBSOCKET,
                                            TransportType.HTTP_UPGRADE,
                                            TransportType.SPLIT_HTTP,
                                        )
                                    },
                                    host = transportHost.trim().takeIf {
                                        it.isNotBlank() && transport in setOf(
                                            TransportType.WEBSOCKET,
                                            TransportType.HTTP_UPGRADE,
                                            TransportType.SPLIT_HTTP,
                                        )
                                    },
                                    serviceName = serviceName.trim().takeIf {
                                        it.isNotBlank() && transport == TransportType.GRPC
                                    },
                                    authority = authority.trim().takeIf {
                                        it.isNotBlank() && transport == TransportType.GRPC
                                    },
                                ),
                            )
                            VlessUriFormatter.format(candidate).also {
                                require(VlessUriParser.parse(it) is ParseResult.Success)
                            }
                        }.getOrNull()
                        if (raw == null) invalid = true else onSave(raw)
                    }) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = InkColor, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun parseVless(raw: String): VlessProfile? = when (val result = VlessUriParser.parse(raw)) {
    is ParseResult.Success -> result.value
    is ParseResult.Error -> null
}

@Composable
fun SubscriptionDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = SurfaceColor) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.subscription_title), style = MaterialTheme.typography.titleLarge, color = InkColor)
                EditorField(name, { name = it }, R.string.subscription_name)
                EditorField(url, { url = it }, R.string.subscription_url, KeyboardType.Uri)
                if (invalid) Text(stringResource(R.string.subscription_https_required), color = ErrorColor)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        val valid = name.isNotBlank() && runCatching {
                            val parsed = java.net.URI(url.trim())
                            parsed.scheme.equals("https", true) &&
                                !parsed.host.isNullOrBlank() &&
                                parsed.userInfo == null &&
                                parsed.fragment == null
                        }.getOrDefault(false)
                        if (valid) onSave(name.trim(), url.trim()) else invalid = true
                    }) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

@Composable
fun BackupPassphraseDialog(
    restoring: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember(restoring) { mutableStateOf("") }
    var confirmation by remember(restoring) { mutableStateOf("") }
    var invalid by remember(restoring) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = SurfaceColor) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (restoring) R.string.backup_restore else R.string.backup_export),
                    style = MaterialTheme.typography.titleLarge,
                    color = InkColor,
                )
                Text(
                    stringResource(
                        if (restoring) R.string.backup_restore_warning else R.string.backup_passphrase_hint,
                    ),
                    color = if (restoring) ErrorColor else MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(MAX_BACKUP_PASSPHRASE_CHARS); invalid = false },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    isError = invalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!restoring) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.take(MAX_BACKUP_PASSPHRASE_CHARS); invalid = false },
                        label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        isError = invalid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (invalid) {
                    Text(
                        stringResource(
                            if (restoring) R.string.backup_passphrase_required else R.string.backup_passphrase_invalid,
                        ),
                        color = ErrorColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        val valid = if (restoring) {
                            passphrase.isNotEmpty()
                        } else {
                            passphrase.length >= MIN_BACKUP_PASSPHRASE_CHARS && passphrase == confirmation
                        }
                        if (valid) onConfirm(passphrase) else invalid = true
                    }) {
                        Text(stringResource(if (restoring) R.string.backup_restore_confirm else R.string.continue_action))
                    }
                }
            }
        }
    }
}

private const val MIN_BACKUP_PASSPHRASE_CHARS = 12
private const val MAX_BACKUP_PASSPHRASE_CHARS = 1_024
