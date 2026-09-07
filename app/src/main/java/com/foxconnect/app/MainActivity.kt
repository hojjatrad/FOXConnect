package com.foxconnect.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.foxconnect.app.importer.SubscriptionClient
import com.foxconnect.app.importer.SubscriptionFetchResult
import com.foxconnect.app.importer.readAtMost
import com.foxconnect.app.ui.BackupPassphraseDialog
import com.foxconnect.app.ui.FoxConnectTheme
import com.foxconnect.app.ui.HomeScreen
import com.foxconnect.app.ui.LogsScreen
import com.foxconnect.app.ui.ProductSettings
import com.foxconnect.app.ui.ProfilesScreen
import com.foxconnect.app.ui.SettingsDialog
import com.foxconnect.app.ui.SubscriptionDialog
import com.foxconnect.app.ui.VlessEditorDialog
import com.foxconnect.core.engine.AndroidTunnelController
import com.foxconnect.core.engine.FoxVpnService
import com.foxconnect.core.engine.ProfileHealthStore
import com.foxconnect.core.engine.TunnelEventLog
import com.foxconnect.core.engine.TunnelRuntime
import com.foxconnect.core.model.AnyTlsProfile
import com.foxconnect.core.model.ConnectableProfile
import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.model.HttpProxyProfile
import com.foxconnect.core.model.Hysteria2Profile
import com.foxconnect.core.model.HysteriaProfile
import com.foxconnect.core.model.ManagedProfile
import com.foxconnect.core.model.ProfileSource
import com.foxconnect.core.model.ShadowsocksProfile
import com.foxconnect.core.model.Socks5Profile
import com.foxconnect.core.model.TrojanProfile
import com.foxconnect.core.model.TuicProfile
import com.foxconnect.core.model.VlessProfile
import com.foxconnect.core.model.VmessProfile
import com.foxconnect.core.model.WireGuardProfile
import com.foxconnect.core.parser.ConfigImportBatch
import com.foxconnect.core.parser.ConnectableProfileParser
import com.foxconnect.core.parser.ParseResult
import com.foxconnect.core.parser.UniversalConfigImporter
import com.foxconnect.core.storage.EncryptedProfileBackup
import com.foxconnect.core.storage.ProfileMutationResult
import com.foxconnect.core.storage.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var controller: AndroidTunnelController
    private lateinit var profiles: ProfileRepository
    private lateinit var profileHealthStore: ProfileHealthStore
    private lateinit var tunnelEventLog: TunnelEventLog
    private val destination = MutableStateFlow(Destination.HOME)
    private val backupRequest = MutableStateFlow<BackupRequest?>(null)
    private var permissionPendingConnection: PendingConnection? = null
    private var notificationPendingConnection: PendingConnection? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val connection = permissionPendingConnection
        permissionPendingConnection = null
        if (result.resultCode == RESULT_OK && connection != null) {
            controller.connect(connection.selected, connection.candidates)
        } else {
            controller.reportInputError(getString(R.string.vpn_permission_denied))
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPendingConnection?.let(::requestVpnPermission)
            notificationPendingConnection = null
        }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_IMPORT_BYTES + 1) }
            }
            if (bytes == null) {
                toast(R.string.import_failed)
            } else {
                val batch = withContext(Dispatchers.Default) {
                    UniversalConfigImporter.importBytes(bytes, uri.lastPathSegment)
                }
                importBatch(batch, ProfileSource.FILE)
            }
        }
    }

    private val backupExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            val pending = pendingBackupFile()
            if (uri == null) {
                pending.delete()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val written = try {
                    withContext(Dispatchers.IO) {
                        require(pending.isFile && pending.length() <= EncryptedProfileBackup.MAX_ENVELOPE_BYTES)
                        contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            pending.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Backup destination is unavailable")
                    }
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                } finally {
                    pending.delete()
                }
                toast(if (written) R.string.backup_export_success else R.string.backup_failed)
            }
        }

    private val backupRestoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { backupRequest.value = BackupRequest.Restore(it) }
    }

    private val qrScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_RAW)
        if (result.resultCode != RESULT_OK || raw.isNullOrBlank()) return@registerForActivityResult
        lifecycleScope.launch {
            val batch = withContext(Dispatchers.Default) { UniversalConfigImporter.importText(raw) }
            importBatch(batch, ProfileSource.QR)
        }
    }

    private val qrPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchQrCamera() else toast(R.string.qr_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AndroidTunnelController(this)
        profiles = (application as FoxConnectApplication).profileRepository
        profileHealthStore = ProfileHealthStore(this)
        tunnelEventLog = TunnelEventLog(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (destination.value != Destination.HOME) {
                    destination.value = Destination.HOME
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        consumeImportIntent(intent)
        setContent {
            FoxConnectTheme {
                val snapshot by TunnelRuntime.snapshot.collectAsStateWithLifecycle()
                val repositoryState by profiles.state.collectAsStateWithLifecycle()
                val currentDestination by destination.collectAsStateWithLifecycle()
                val currentBackupRequest by backupRequest.collectAsStateWithLifecycle()
                val connectableProfiles = remember(repositoryState.profiles) {
                    repositoryState.profiles.mapNotNull { it.toConnectableProfile() }
                }
                val selected = remember(repositoryState.selectedProfileId, connectableProfiles) {
                    connectableProfiles.firstOrNull { it.id == repositoryState.selectedProfileId }
                }
                var healthRevision by remember { mutableStateOf(0) }
                val healthMetrics = remember(
                    repositoryState.profiles,
                    snapshot.lastHealthCheckEpochMs,
                    currentDestination,
                    healthRevision,
                ) {
                    profileHealthStore.read(repositoryState.profiles.map { it.id })
                }
                var pingRunning by remember { mutableStateOf(false) }
                var logsRevision by remember { mutableStateOf(0) }
                val logEntries = remember(currentDestination, snapshot.state, logsRevision) {
                    tunnelEventLog.read()
                }
                var editorVisible by remember { mutableStateOf(false) }
                var editorTarget by remember { mutableStateOf<ManagedProfile?>(null) }
                var subscriptionVisible by remember { mutableStateOf(false) }
                var settingsVisible by remember { mutableStateOf(false) }
                var deleteTarget by remember { mutableStateOf<ManagedProfile?>(null) }

                when (currentDestination) {
                    Destination.HOME -> HomeScreen(
                        snapshot = snapshot,
                        selectedProfile = selected,
                        onConnectionClick = {
                            toggleConnection(snapshot.state, selected, connectableProfiles)
                        },
                        onProfileClick = { destination.value = Destination.PROFILES },
                        onSettingsClick = { settingsVisible = true },
                        onLogsClick = { destination.value = Destination.LOGS },
                        onConfigsClick = { destination.value = Destination.PROFILES },
                    )
                    Destination.PROFILES -> ProfilesScreen(
                        state = repositoryState,
                        healthMetrics = healthMetrics,
                        pingRunning = pingRunning,
                        failoverEnabled = loadProductSettings().failoverEnabled,
                        onBack = { destination.value = Destination.HOME },
                        onPingAll = {
                            if (snapshot.state.isTunnelActive()) {
                                toast(R.string.disconnect_before_ping)
                            } else if (!pingRunning) {
                                pingRunning = true
                                lifecycleScope.launch {
                                    val outcomes = ProfileLatencyTester.testAll(connectableProfiles)
                                    outcomes.forEach { (id, outcome) ->
                                        when (outcome) {
                                            is ProfileLatencyTester.Outcome.Reachable ->
                                                profileHealthStore.recordSuccess(id, outcome.latencyMs)
                                            ProfileLatencyTester.Outcome.Unreachable ->
                                                profileHealthStore.recordEndpointProbeFailure(id)
                                            ProfileLatencyTester.Outcome.Unsupported -> Unit
                                        }
                                    }
                                    healthRevision++
                                    pingRunning = false
                                    val reachable = outcomes.values.count {
                                        it is ProfileLatencyTester.Outcome.Reachable
                                    }
                                    val failed = outcomes.values.count {
                                        it is ProfileLatencyTester.Outcome.Unreachable
                                    }
                                    toast(getString(R.string.ping_all_finished, reachable, failed))
                                }
                            }
                        },
                        onRefreshAllSubscriptions = {
                            repositoryState.subscriptions.filter { it.enabled }.forEach {
                                syncSubscription(it.name, it.url)
                            }
                            if (repositoryState.subscriptions.none { it.enabled }) {
                                toast(R.string.no_enabled_subscriptions)
                            }
                        },
                        onSelect = { profile ->
                            if (
                                snapshot.state is ConnectionState.Connected ||
                                snapshot.state is ConnectionState.Connecting ||
                                snapshot.state is ConnectionState.Switching
                            ) {
                                toast(R.string.disconnect_before_profile_change)
                            } else {
                                lifecycleScope.launch {
                                    if (profiles.select(profile.id) is ProfileMutationResult.Success) {
                                        controller.clearPersistedConnection()
                                        toast(getString(R.string.profile_selected, profile.name))
                                        destination.value = Destination.HOME
                                    }
                                }
                            }
                        },
                        onClipboard = ::importClipboard,
                        onFile = { fileLauncher.launch(arrayOf("text/*", "application/zip", "application/gzip", "application/octet-stream")) },
                        onQr = ::startQrScan,
                        onManual = { editorTarget = null; editorVisible = true },
                        onSubscription = { subscriptionVisible = true },
                        onExportBackup = ::startBackupExport,
                        onRestoreBackup = ::startBackupRestore,
                        onEdit = {
                            if (it.protocol == com.foxconnect.core.model.ProtocolType.VLESS) {
                                editorTarget = it
                                editorVisible = true
                            } else {
                                showComingSoon()
                            }
                        },
                        onDuplicate = { profile ->
                            lifecycleScope.launch {
                                profiles.duplicate(profile.id, getString(R.string.profile_copy_suffix, profile.name))
                            }
                        },
                        onDelete = { deleteTarget = it },
                        onFavorite = { profile, favorite ->
                            lifecycleScope.launch { profiles.setFavorite(profile.id, favorite) }
                        },
                        onRefreshSubscription = { subscription ->
                            syncSubscription(subscription.name, subscription.url)
                        },
                        onSetSubscriptionEnabled = { subscriptionId, enabled ->
                            lifecycleScope.launch { profiles.setSubscriptionEnabled(subscriptionId, enabled) }
                        },
                        onDeleteSubscription = { subscriptionId ->
                            lifecycleScope.launch {
                                if (profiles.state.value.selectedProfile?.subscriptionId == subscriptionId) {
                                    controller.clearPersistedConnection()
                                }
                                profiles.deleteSubscription(subscriptionId)
                            }
                        },
                    )
                    Destination.LOGS -> LogsScreen(
                        entries = logEntries,
                        onBack = { destination.value = Destination.HOME },
                        onClear = {
                            tunnelEventLog.clear()
                            logsRevision++
                        },
                    )
                }

                if (editorVisible) {
                    VlessEditorDialog(
                        managedProfile = editorTarget,
                        onDismiss = { editorVisible = false },
                        onSave = { raw ->
                            val target = editorTarget
                            editorVisible = false
                            if (target == null) {
                                lifecycleScope.launch {
                                    val batch = withContext(Dispatchers.Default) {
                                        UniversalConfigImporter.importText(raw)
                                    }
                                    importBatch(batch, ProfileSource.MANUAL)
                                }
                            } else {
                                lifecycleScope.launch {
                                    val imported = withContext(Dispatchers.Default) {
                                        UniversalConfigImporter.importText(raw).configs.singleOrNull()
                                    }
                                    if (imported == null || profiles.updateConfig(target.id, imported) !is ProfileMutationResult.Success) {
                                        toast(R.string.import_failed)
                                    } else if (target.id == profiles.state.value.selectedProfileId) {
                                        controller.clearPersistedConnection()
                                    }
                                }
                            }
                        },
                    )
                }
                if (subscriptionVisible) {
                    SubscriptionDialog(
                        onDismiss = { subscriptionVisible = false },
                        onSave = { name, url ->
                            subscriptionVisible = false
                            syncSubscription(name, url)
                        },
                    )
                }
                if (settingsVisible) {
                    SettingsDialog(
                        initial = loadProductSettings(),
                        onDismiss = { settingsVisible = false },
                        onSave = { value ->
                            saveProductSettings(value)
                            settingsVisible = false
                            toast(R.string.settings_saved)
                        },
                        onOpenSystemVpnSettings = {
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
                        },
                    )
                }
                currentBackupRequest?.let { request ->
                    BackupPassphraseDialog(
                        restoring = request is BackupRequest.Restore,
                        onDismiss = { backupRequest.value = null },
                        onConfirm = { passphrase ->
                            backupRequest.value = null
                            processBackupRequest(request, passphrase)
                        },
                    )
                }
                deleteTarget?.let { target ->
                    AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text(getString(R.string.profile_delete)) },
                        text = { Text(target.name) },
                        confirmButton = {
                            TextButton(onClick = {
                                deleteTarget = null
                                lifecycleScope.launch {
                                    if (target.id == profiles.state.value.selectedProfileId) {
                                        controller.clearPersistedConnection()
                                    }
                                    profiles.delete(target.id)
                                    profileHealthStore.remove(target.id)
                                }
                            }) { Text(getString(R.string.profile_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteTarget = null }) { Text(getString(R.string.cancel)) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeImportIntent(intent)
    }

    private fun startBackupExport() {
        if (TunnelRuntime.snapshot.value.state.isTunnelActive()) {
            toast(R.string.disconnect_before_profile_change)
            return
        }
        backupRequest.value = BackupRequest.Export
    }

    private fun startBackupRestore() {
        if (TunnelRuntime.snapshot.value.state.isTunnelActive()) {
            toast(R.string.disconnect_before_profile_change)
            return
        }
        backupRestoreLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "application/octet-stream"))
    }

    private fun processBackupRequest(request: BackupRequest, passphrase: String) {
        if (TunnelRuntime.snapshot.value.state.isTunnelActive()) {
            toast(R.string.disconnect_before_profile_change)
            return
        }
        when (request) {
            BackupRequest.Export -> prepareBackupExport(passphrase)
            is BackupRequest.Restore -> restoreBackup(request.uri, passphrase)
        }
    }

    private fun prepareBackupExport(passphrase: String) {
        lifecycleScope.launch {
            val encrypted = profiles.exportEncryptedBackup(passphrase.toCharArray()).getOrElse {
                toast(R.string.backup_failed)
                return@launch
            }
            val prepared = try {
                withContext(Dispatchers.IO) {
                    val target = pendingBackupFile()
                    target.parentFile?.mkdirs()
                    java.io.FileOutputStream(target, false).use { output ->
                        output.write(encrypted)
                        output.fd.sync()
                    }
                    true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            } finally {
                encrypted.fill(0)
            }
            if (!prepared) {
                pendingBackupFile().delete()
                toast(R.string.backup_failed)
                return@launch
            }
            runCatching {
                backupExportLauncher.launch("FOXConnect-backup-${java.time.LocalDate.now()}.foxbackup")
            }.onFailure {
                pendingBackupFile().delete()
                toast(R.string.backup_failed)
            }
        }
    }

    private fun restoreBackup(uri: Uri, passphrase: String) {
        lifecycleScope.launch {
            val encrypted = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use {
                        it.readAtMost(EncryptedProfileBackup.MAX_ENVELOPE_BYTES + 1)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (encrypted == null || encrypted.size > EncryptedProfileBackup.MAX_ENVELOPE_BYTES) {
                encrypted?.fill(0)
                toast(R.string.backup_failed)
                return@launch
            }
            val restored = try {
                profiles.restoreEncryptedBackup(encrypted, passphrase.toCharArray())
            } finally {
                encrypted.fill(0)
            }
            restored.fold(
                onSuccess = { summary ->
                    controller.clearPersistedConnection()
                    toast(getString(R.string.backup_restore_success, summary.profiles, summary.subscriptions))
                },
                onFailure = { toast(R.string.backup_failed) },
            )
        }
    }

    private fun pendingBackupFile() = cacheDir.resolve("pending-profile-backup.enc")

    private fun ConnectionState.isTunnelActive(): Boolean =
        this is ConnectionState.Connected || this is ConnectionState.Connecting || this is ConnectionState.Switching

    private fun startQrScan() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast(R.string.qr_scan_failed)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchQrCamera()
        } else {
            qrPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrCamera() {
        runCatching { qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java)) }
            .onFailure { toast(R.string.qr_scan_failed) }
    }

    private fun toggleConnection(
        state: ConnectionState,
        profile: ConnectableProfile?,
        candidates: List<ConnectableProfile>,
    ) {
        if (state is ConnectionState.Connecting || state is ConnectionState.Connected || state is ConnectionState.Switching) {
            controller.disconnect()
            return
        }
        if (profile == null) {
            controller.reportInputError(getString(R.string.no_config), getString(R.string.choose_config_hint))
            return
        }
        val connection = PendingConnection(profile, candidates)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPendingConnection = connection
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermission(connection)
        }
    }

    private fun requestVpnPermission(connection: PendingConnection) {
        val permissionIntent = controller.permissionIntent()
        if (permissionIntent == null) {
            controller.connect(connection.selected, connection.candidates)
        } else {
            permissionPendingConnection = connection
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun consumeImportIntent(intent: Intent?) {
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        lifecycleScope.launch {
            val batch = withContext(Dispatchers.Default) { UniversalConfigImporter.importText(raw) }
            importBatch(batch, ProfileSource.SHARE)
        }
        intent?.action = null
    }

    private fun importClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast(R.string.clipboard_empty)
            return
        }
        lifecycleScope.launch {
            val batch = withContext(Dispatchers.Default) { UniversalConfigImporter.importText(text) }
            importBatch(batch, ProfileSource.CLIPBOARD)
        }
    }

    private suspend fun importBatch(batch: ConfigImportBatch, source: ProfileSource) {
        if (batch.configs.isEmpty()) {
            toast(R.string.import_failed)
            return
        }
        val wasEmpty = profiles.state.value.profiles.isEmpty()
        profiles.import(batch.configs, source).fold(
            onSuccess = { summary ->
                if (wasEmpty && summary.added > 0) controller.clearPersistedConnection()
                toast(getString(R.string.import_success, summary.added, summary.updated))
                if (batch.issues.isNotEmpty()) {
                    toast(getString(R.string.import_partial, batch.configs.size, batch.issues.size))
                }
            },
            onFailure = { toast(R.string.profiles_storage_error) },
        )
    }

    private fun syncSubscription(name: String, url: String) {
        lifecycleScope.launch {
            val wasEmpty = profiles.state.value.profiles.isEmpty()
            val existing = profiles.state.value.subscriptions.firstOrNull { it.url == url }
            val updatesSelected = existing?.id != null &&
                profiles.state.value.selectedProfile?.subscriptionId == existing.id
            val tunnelState = TunnelRuntime.snapshot.value.state
            if (
                updatesSelected &&
                (tunnelState is ConnectionState.Connected ||
                    tunnelState is ConnectionState.Connecting ||
                    tunnelState is ConnectionState.Switching)
            ) {
                toast(R.string.disconnect_before_profile_change)
                return@launch
            }
            // Persist first so a bad first response does not make the subscription
            // disappear. The user can see its failure state and refresh it later.
            val subscriptionId = profiles.upsertSubscription(name, url, existing?.lastError)
                .getOrElse {
                    toast(R.string.profiles_storage_error)
                    return@launch
                }
            when (val fetched = SubscriptionClient().fetch(url, existing?.etag)) {
                SubscriptionFetchResult.NotModified -> {
                    profiles.markSubscriptionChecked(subscriptionId)
                    toast(R.string.subscription_not_modified)
                }
                is SubscriptionFetchResult.Failure -> {
                    profiles.markSubscriptionFailure(subscriptionId, fetched.errorCode)
                    toast(R.string.subscription_fetch_failed)
                }
                is SubscriptionFetchResult.Success -> {
                    val batch = withContext(Dispatchers.Default) {
                        UniversalConfigImporter.importBytes(fetched.body, name)
                    }
                    if (batch.configs.isEmpty()) {
                        profiles.markSubscriptionFailure(subscriptionId, "subscription_no_supported_configs")
                        toast(R.string.subscription_no_supported_configs)
                        return@launch
                    }
                    profiles.syncSubscription(name, url, batch.configs, fetched.etag).fold(
                        onSuccess = { summary ->
                            if (updatesSelected || (wasEmpty && summary.added > 0)) {
                                controller.clearPersistedConnection()
                            }
                            toast(
                                getString(
                                    R.string.subscription_sync_success,
                                    summary.added,
                                    summary.updated,
                                    summary.removed,
                                ),
                            )
                        },
                        onFailure = { toast(R.string.profiles_storage_error) },
                    )
                }
            }
        }
    }

    private fun ManagedProfile.toConnectableProfile(): ConnectableProfile? =
        when (val result = ConnectableProfileParser.parse(rawConfig)) {
            is ParseResult.Success -> when (val profile = result.value) {
                is VlessProfile -> profile.copy(id = id, name = name)
                is VmessProfile -> profile.copy(id = id, name = name)
                is TrojanProfile -> profile.copy(id = id, name = name)
                is ShadowsocksProfile -> profile.copy(id = id, name = name)
                is Hysteria2Profile -> profile.copy(id = id, name = name)
                is HysteriaProfile -> profile.copy(id = id, name = name)
                is TuicProfile -> profile.copy(id = id, name = name)
                is AnyTlsProfile -> profile.copy(id = id, name = name)
                is WireGuardProfile -> profile.copy(id = id, name = name)
                is Socks5Profile -> profile.copy(id = id, name = name)
                is HttpProxyProfile -> profile.copy(id = id, name = name)
            }
            is ParseResult.Error -> null
        }

    private fun loadProductSettings(): ProductSettings {
        val preferences = getSharedPreferences(FoxVpnService.PRODUCT_PREFERENCES, MODE_PRIVATE)
        return ProductSettings(
            autoConnect = preferences.getBoolean(AUTO_CONNECT_KEY, false),
            failoverEnabled = preferences.getBoolean(FoxVpnService.KEY_FAILOVER_ENABLED, true),
            returnToPreferred = preferences.getBoolean(FoxVpnService.KEY_RETURN_TO_PREFERRED, false),
            killSwitchEnabled = preferences.getBoolean(FoxVpnService.KEY_KILL_SWITCH_ENABLED, true),
            cooldownSeconds = (preferences.getLong(FoxVpnService.KEY_FAILOVER_COOLDOWN_MS, 60_000) / 1_000)
                .toInt().coerceIn(30, 120),
        )
    }

    private fun saveProductSettings(value: ProductSettings) {
        getSharedPreferences(FoxVpnService.PRODUCT_PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(AUTO_CONNECT_KEY, value.autoConnect)
            .putBoolean(FoxVpnService.KEY_FAILOVER_ENABLED, value.failoverEnabled)
            .putBoolean(FoxVpnService.KEY_RETURN_TO_PREFERRED, value.returnToPreferred)
            .putBoolean(FoxVpnService.KEY_KILL_SWITCH_ENABLED, value.killSwitchEnabled)
            .putLong(FoxVpnService.KEY_FAILOVER_COOLDOWN_MS, value.cooldownSeconds * 1_000L)
            .apply()
    }

    private fun showComingSoon() = toast(R.string.coming_phase)

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private enum class Destination { HOME, PROFILES, LOGS }

    private data class PendingConnection(
        val selected: ConnectableProfile,
        val candidates: List<ConnectableProfile>,
    )

    private sealed interface BackupRequest {
        data object Export : BackupRequest
        data class Restore(val uri: Uri) : BackupRequest
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        const val BACKUP_MIME_TYPE = "application/vnd.foxconnect.backup"
        const val AUTO_CONNECT_KEY = "auto_connect"
    }
}
