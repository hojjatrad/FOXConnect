package com.foxconnect.core.engine

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

/**
 * Typed adapter for the exact pinned libbox API. The old reflective adapter
 * could return null or a value of the wrong type across JNI, which can abort
 * the whole Android process. Keeping the native core in :vpn and using typed
 * callbacks prevents that class of process-ending failure.
 */
internal data class NativeTrafficSnapshot(
    val rxBytes: Long,
    val txBytes: Long,
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long,
)

internal object TunRoutePolicy {
    fun requiresDefaultRoute(explicitRouteCount: Int, hasAddressFamily: Boolean): Boolean {
        require(explicitRouteCount >= 0)
        return explicitRouteCount == 0 && hasAddressFamily
    }
}

internal enum class CoreStartStage(val code: String) {
    SETUP("core_setup_failed"),
    VERSION("core_version_failed"),
    CONFIG_CHECK("core_config_check_failed"),
    COMMAND_CREATE("core_command_create_failed"),
    COMMAND_START("core_command_start_failed"),
    NETWORK_MONITOR("core_network_monitor_failed"),
    SERVICE_START("core_service_start_failed"),
    POST_START("core_post_start_failed"),
}

internal class CoreStartFailure(
    val stage: CoreStartStage,
    cause: Throwable,
) : IllegalStateException(stage.code, cause)

private inline fun <T> atCoreStartStage(stage: CoreStartStage, block: () -> T): T = try {
    block()
} catch (error: CoreStartFailure) {
    throw error
} catch (error: Throwable) {
    throw CoreStartFailure(stage, error)
}

internal class TypedLibboxCore(
    private val service: VpnService,
    private val onCoreStopRequested: () -> Unit,
    private val beforeTunEstablish: () -> Unit = {},
) : AutoCloseable, CommandServerHandler, PlatformInterface {
    private val closing = AtomicBoolean(false)
    private val stopReported = AtomicBoolean(false)
    private val dataPathDiagnostics = DataPathDiagnostics()
    private val networkMonitor = PhysicalNetworkMonitor(service) { available ->
        dataPathDiagnostics.recordPhysicalNetwork(available)
    }
    private val localResolver = AndroidLocalResolver(networkMonitor, dataPathDiagnostics)
    private val traffic = AtomicReference<NativeTrafficSnapshot?>(null)
    private var commandServer: CommandServer? = null
    private var statusClient: CommandClient? = null
    private var tunDescriptor: ParcelFileDescriptor? = null

    val available: Boolean
        get() = true

    fun dataPathSnapshot(): DataPathSnapshot = dataPathDiagnostics.snapshot()

    fun trafficSnapshot(): NativeTrafficSnapshot? = traffic.get()

    fun start(configJson: String) {
        check(!closing.get()) { "libbox_core_closed" }
        atCoreStartStage(CoreStartStage.SETUP) { setupLibbox() }
        atCoreStartStage(CoreStartStage.VERSION) {
            check(Libbox.version().startsWith(EXPECTED_CORE_VERSION)) { "libbox_version_mismatch" }
        }
        atCoreStartStage(CoreStartStage.CONFIG_CHECK) { Libbox.checkConfig(configJson) }

        // Match the authoritative Android integration order: promote the native
        // OOM draft, own and start the command server, then register physical
        // network tracking before starting the service.
        val server = atCoreStartStage(CoreStartStage.COMMAND_CREATE) {
            Libbox.promoteOOMDraft()
            CommandServer(this, this)
        }
        commandServer = server // Must be owned before any callback or failure.
        try {
            atCoreStartStage(CoreStartStage.COMMAND_START) { server.start() }
            atCoreStartStage(CoreStartStage.NETWORK_MONITOR) { networkMonitor.start() }
            atCoreStartStage(CoreStartStage.SERVICE_START) {
                server.startOrReloadService(configJson, OverrideOptions())
            }
            atCoreStartStage(CoreStartStage.POST_START) {
                // Process lookup is implemented by findConnectionOwner on API
                // 29+, and by libbox procfs lookup on older Android versions.
                if (server.needWIFIState()) server.updateWIFIState()
                // Status accounting is observational; a vendor-specific command
                // client failure must not tear down an otherwise verified VPN.
                runCatching { startStatusClient() }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        val client = statusClient
        statusClient = null
        runCatching { client?.disconnect() }
        traffic.set(null)
        val server = commandServer
        commandServer = null
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        networkMonitor.close()
    }

    private fun startStatusClient() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            statusInterval = STATUS_INTERVAL_NS
        }
        val client = CommandClient(object : CommandClientHandler {
            override fun connected() = Unit
            override fun disconnected(message: String?) {
                traffic.set(null)
            }
            override fun writeStatus(message: StatusMessage) {
                if (closing.get() || !message.trafficAvailable) return
                traffic.set(
                    NativeTrafficSnapshot(
                        rxBytes = message.downlinkTotal.coerceAtLeast(0),
                        txBytes = message.uplinkTotal.coerceAtLeast(0),
                        rxBytesPerSecond = message.downlink.coerceAtLeast(0),
                        txBytesPerSecond = message.uplink.coerceAtLeast(0),
                    ),
                )
            }
            override fun setDefaultLogLevel(level: Int) = Unit
            override fun clearLogs() = Unit
            override fun writeLogs(messageList: LogIterator?) = Unit
            override fun writeGroups(message: OutboundGroupIterator?) = Unit
            override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
            override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
            override fun updateClashMode(newMode: String) = Unit
            override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        }, options)
        statusClient = client
        client.connect()
    }

    // CommandServerHandler -------------------------------------------------

    override fun serviceStop() {
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        if (!closing.get() && stopReported.compareAndSet(false, true)) {
            onCoreStopRequested()
        }
    }

    override fun serviceReload() = Unit

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun connectSSHAgent(): Int = -1

    override fun triggerNativeCrash() = Unit

    // Never forward native debug text to logcat; it can contain endpoint data.
    override fun writeDebugMessage(message: String?) = Unit

    // PlatformInterface ----------------------------------------------------

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(fd >= 0 && service.protect(fd)) { "protect_outbound_socket_failed" }
        dataPathDiagnostics.recordProtectedSocket()
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(service) == null) { "vpn_permission_revoked" }
        beforeTunEstablish()
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null

        val builder = service.Builder()
            .setSession("FOXConnect")
            .setMtu(options.mtu.coerceIn(MIN_MTU, MAX_MTU))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        networkMonitor.current()?.let { physical ->
            // Declare the actual uplink to Android without allowing application bypass.
            builder.setUnderlyingNetworks(arrayOf(physical))
        }

        var hasInet4 = false
        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
            hasInet4 = true
        }
        var hasInet6 = false
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val prefix = inet6.next()
            builder.addAddress(prefix.address(), prefix.prefix())
            hasInet6 = true
        }
        check(hasInet4 || hasInet6) { "tun_has_no_address" }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            addRoutes(builder, options, hasInet4, hasInet6)
            addApplicationRules(builder, options)
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bypass = options.httpProxyBypassDomain.toMutableList()
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(options.httpProxyServer, options.httpProxyServerPort, bypass),
            )
        }

        val descriptor = builder.establish() ?: error("tun_establish_failed")
        tunDescriptor = descriptor
        dataPathDiagnostics.recordTunEstablished()
        return descriptor.fd
    }

    private fun addRoutes(
        builder: VpnService.Builder,
        options: TunOptions,
        hasInet4: Boolean,
        hasInet6: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var inet4Routes = 0
            val route4 = options.inet4RouteAddress
            while (route4.hasNext()) {
                val prefix = route4.next()
                builder.addRoute(IpPrefix(java.net.InetAddress.getByName(prefix.address()), prefix.prefix()))
                inet4Routes++
            }
            if (inet4Routes == 0 && hasInet4) builder.addRoute("0.0.0.0", 0)

            var inet6Routes = 0
            val route6 = options.inet6RouteAddress
            while (route6.hasNext()) {
                val prefix = route6.next()
                builder.addRoute(IpPrefix(java.net.InetAddress.getByName(prefix.address()), prefix.prefix()))
                inet6Routes++
            }
            if (inet6Routes == 0 && hasInet6) builder.addRoute("::", 0)

            val exclude4 = options.inet4RouteExcludeAddress
            while (exclude4.hasNext()) {
                val prefix = exclude4.next()
                builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(prefix.address()), prefix.prefix()))
            }
            val exclude6 = options.inet6RouteExcludeAddress
            while (exclude6.hasNext()) {
                val prefix = exclude6.next()
                builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(prefix.address()), prefix.prefix()))
            }
        } else {
            var inet4Routes = 0
            val route4 = options.inet4RouteRange
            while (route4.hasNext()) {
                val prefix = route4.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                inet4Routes++
            }
            if (TunRoutePolicy.requiresDefaultRoute(inet4Routes, hasInet4)) {
                builder.addRoute("0.0.0.0", 0)
            }

            var inet6Routes = 0
            val route6 = options.inet6RouteRange
            while (route6.hasNext()) {
                val prefix = route6.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                inet6Routes++
            }
            if (TunRoutePolicy.requiresDefaultRoute(inet6Routes, hasInet6)) {
                builder.addRoute("::", 0)
            }
        }
    }

    private fun addApplicationRules(builder: VpnService.Builder, options: TunOptions) {
        val include = options.includePackage
        while (include.hasNext()) {
            runCatching { builder.addAllowedApplication(include.next()) }
                .onFailure { if (it !is PackageManager.NameNotFoundException) throw it }
        }
        val exclude = options.excludePackage
        while (exclude.hasNext()) {
            runCatching { builder.addDisallowedApplication(exclude.next()) }
                .onFailure { if (it !is PackageManager.NameNotFoundException) throw it }
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        val values = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val name = link.interfaceName ?: return@mapNotNull null
            val system = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: return@mapNotNull null
            runCatching {
                BoxNetworkInterface().apply {
                    this.name = name
                    index = system.index
                    mtu = runCatching { system.mtu }.getOrDefault(1_500)
                    type = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    addresses = system.interfaceAddresses.mapNotNull { address ->
                        address.address.hostAddress?.substringBefore('%')?.let {
                            "$it/${address.networkPrefixLength.toInt()}"
                        }
                    }.asStringIterator()
                    dnsServer = link.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }.asStringIterator()
                    gateway = link.routes.mapNotNull { route ->
                        route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress?.substringBefore('%')
                    }.asStringIterator()
                    flags = buildInterfaceFlags(system, capabilities)
                    metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }.getOrNull()
        }
        return values.asNetworkInterfaceIterator()
    }

    private fun buildInterfaceFlags(
        networkInterface: NetworkInterface,
        capabilities: NetworkCapabilities,
    ): Int {
        var flags = 0
        if (runCatching { networkInterface.isUp }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_UP
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = flags or OsConstants.IFF_RUNNING
            }
        }
        if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) flags = flags or OsConstants.IFF_LOOPBACK
        if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) flags = flags or OsConstants.IFF_POINTOPOINT
        if (runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)) flags = flags or OsConstants.IFF_MULTICAST
        return flags
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        networkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        networkMonitor.setListener(null)
    }

    override fun localDNSTransport(): LocalDNSTransport = localResolver

    override fun readWIFIState(): WIFIState = WIFIState("", "")

    override fun clearDNSCache() = Unit
    override fun includeAllNetworks(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun registerMyInterface(name: String?) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun sendNotification(notification: Notification?) = Unit
    override fun cancelNotification(identifier: String?, typeID: Int) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell(): Unit = error("platform_shell_disabled")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("platform_shell_disabled")
    override fun readSystemSSHHostKey(): String = error("platform_shell_disabled")
    override fun lookupSFTPServer(): String = error("platform_shell_disabled")
    override fun lookupUser(username: String?): PlatformUser = error("platform_user_lookup_disabled")
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("platform_bridge_disabled")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "process_lookup_requires_android_10" }
        val source = InetSocketAddress(checkNotNull(sourceAddress), sourcePort)
        val destination = InetSocketAddress(checkNotNull(destinationAddress), destinationPort)
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        val uid = connectivity.getConnectionOwnerUid(ipProtocol, source, destination)
        check(uid >= 0) { "process_owner_not_found" }
        val packageNames = service.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return ConnectionOwner().apply {
            userId = uid
            userName = packageNames.firstOrNull().orEmpty()
            processPath = ""
            setAndroidPackageNames(packageNames.asStringIterator())
        }
    }
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun setupLibbox() {
        if (!isSetup.compareAndSet(false, true)) return
        try {
            val packageInfo = service.packageManager.getPackageInfo(service.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            }
            val working = service.getExternalFilesDir(null) ?: service.filesDir
            listOf(service.filesDir, working, service.cacheDir).forEach { directory ->
                check(directory.isDirectory || directory.mkdirs()) { "libbox_directory_unavailable" }
            }
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            Libbox.setup(SetupOptions().apply {
                basePath = service.filesDir.absolutePath
                workingPath = working.absolutePath
                tempPath = service.cacheDir.absolutePath
                fixAndroidStack = true
                logMaxLines = 256
                debug = false
                crashReportSource = ""
                appVersion = versionCode.toString()
                appMarketingVersion = packageInfo.versionName.orEmpty()
                oomKillerDisabled = true
                powerReportEnabled = false
            })
        } catch (error: Throwable) {
            isSetup.set(false)
            throw error
        }
    }

    private fun StringIterator.toMutableList(): MutableList<String> = buildList {
        while (this@toMutableList.hasNext()) add(this@toMutableList.next())
    }.toMutableList()

    private fun List<String>.asStringIterator(): StringIterator = object : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < size
        override fun next(): String = get(index++)
        override fun len(): Int = size
    }

    private fun List<BoxNetworkInterface>.asNetworkInterfaceIterator(): NetworkInterfaceIterator =
        object : NetworkInterfaceIterator {
            private var index = 0
            override fun hasNext(): Boolean = index < size
            override fun next(): BoxNetworkInterface = get(index++)
        }

    private companion object {
        const val EXPECTED_CORE_VERSION = "1.14.0"
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        const val MIN_MTU = 1_280
        const val MAX_MTU = 9_000
        val isSetup = AtomicBoolean(false)
    }
}

private class PhysicalNetworkMonitor(
    private val service: VpnService,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)
    @Volatile private var currentNetwork: Network? = null
    @Volatile private var listener: InterfaceUpdateListener? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isPhysical(network)) update(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (isPhysical(capabilities) && (network == currentNetwork || currentNetwork == null)) update(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == currentNetwork && linkProperties.interfaceName != null) notifyListener(network)
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) update(findPhysicalNetwork())
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        update(findPhysicalNetwork())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val registered = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    // Android 9–11 can return the VPN from an ordinary default
                    // callback, so explicitly request a non-VPN network.
                    connectivity.requestNetwork(request, callback, handler)
                }
                else -> connectivity.registerNetworkCallback(request, callback, handler)
            }
        }.recoverCatching {
            // Listening is a safe fallback on OEM builds that reject an active
            // request; it never turns a registration quirk into a retry loop.
            connectivity.registerNetworkCallback(request, callback, handler)
        }.isSuccess
        callbackRegistered.set(registered)
        if (!registered && currentNetwork == null) {
            started.set(false)
            error("physical_network_monitor_unavailable")
        }
    }

    fun setListener(value: InterfaceUpdateListener?) {
        listener = value
        notifyListener(currentNetwork)
    }

    fun current(): Network? = currentNetwork ?: findPhysicalNetwork()?.also(::update)

    private fun findPhysicalNetwork(): Network? {
        connectivity.activeNetwork?.takeIf(::isPhysical)?.let { return it }
        return connectivity.allNetworks.firstOrNull(::isPhysical)
    }

    private fun isPhysical(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.let(::isPhysical) == true

    private fun isPhysical(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun update(network: Network?) {
        currentNetwork = network
        onAvailabilityChanged(network != null)
        // Keep Android's VPN metadata aligned with Wi-Fi/mobile handovers.
        // This does not exempt applications from the VPN; only protected native
        // upstream sockets can use the declared physical network.
        runCatching { service.setUnderlyingNetworks(network?.let { arrayOf(it) }) }
        notifyListener(network)
    }

    private fun notifyListener(network: Network?) {
        val target = listener ?: return
        if (network == null) {
            runCatching { target.updateDefaultInterface("", -1, false, false) }
            return
        }
        // LinkProperties can briefly lag behind onAvailable. Do not report an
        // existing physical network as absent; retry as the reference Android
        // integration does, then let onLinkPropertiesChanged finish the update.
        repeat(DEFAULT_INTERFACE_LOOKUP_ATTEMPTS) { attempt ->
            val link = connectivity.getLinkProperties(network)
            val name = link?.interfaceName
            val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
            if (name != null && index != null) {
                val capabilities = connectivity.getNetworkCapabilities(network)
                val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                val constrained = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) == false
                runCatching { target.updateDefaultInterface(name, index, metered, constrained) }
                return
            }
            if (attempt + 1 < DEFAULT_INTERFACE_LOOKUP_ATTEMPTS) {
                runCatching { Thread.sleep(DEFAULT_INTERFACE_LOOKUP_DELAY_MS) }
            }
        }
    }

    override fun close() {
        listener = null
        started.set(false)
        if (callbackRegistered.compareAndSet(true, false)) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        currentNetwork = null
        onAvailabilityChanged(false)
    }

    private companion object {
        const val DEFAULT_INTERFACE_LOOKUP_ATTEMPTS = 10
        const val DEFAULT_INTERFACE_LOOKUP_DELAY_MS = 100L
    }
}

private class AndroidLocalResolver(
    private val networkMonitor: PhysicalNetworkMonitor,
    private val diagnostics: DataPathDiagnostics,
) : LocalDNSTransport {
    override fun raw(): Boolean = false

    override fun exchange(ctx: ExchangeContext, message: ByteArray?) {
        ctx.errorCode(SERVFAIL)
    }

    override fun lookup(ctx: ExchangeContext, network: String?, domain: String?) {
        diagnostics.recordBootstrapDnsRequest()
        val target = networkMonitor.current()
        if (target == null || domain.isNullOrBlank()) {
            ctx.errorCode(SERVFAIL)
            return
        }
        try {
            val addresses = target.getAllByName(domain)
                .asSequence()
                .filter { address ->
                    when {
                        network?.endsWith("4") == true -> address.address.size == 4
                        network?.endsWith("6") == true -> address.address.size == 16
                        else -> true
                    }
                }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .distinct()
                .joinToString("\n")
            if (addresses.isBlank()) {
                ctx.errorCode(NXDOMAIN)
            } else {
                diagnostics.recordBootstrapDnsSuccess()
                ctx.success(addresses)
            }
        } catch (_: UnknownHostException) {
            ctx.errorCode(NXDOMAIN)
        } catch (_: Throwable) {
            ctx.errorCode(SERVFAIL)
        }
    }

    private companion object {
        const val SERVFAIL = 2
        const val NXDOMAIN = 3
    }
}
