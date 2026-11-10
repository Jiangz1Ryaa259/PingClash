// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.pingclash.mobile.Mobile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class VpnUiState(
    val phase: String = "idle",
    val message: String = tr(R.string.not_connected),
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    val failures: Long = 0,
    val uploadSpeed: Long = 0,
    val downloadSpeed: Long = 0,
    val uptime: Long = 0,
    val connections: Int = 0,
    val totalConnections: Long = 0,
    val dnsQueries: Long = 0,
    val rejected: Long = 0,
    val heapBytes: Long = 0,
    val goroutines: Int = 0,
    val history: List<SpeedSample> = emptyList(),
    val sessionStartedElapsed: Long = -1,
    val failureCode: String? = null,
    val failureId: Long = 0,
) {
    val stage = VpnPhase.fromWire(phase)
    val active
        get() = stage.active
}

data class SpeedSample(val up: Long, val down: Long)

internal fun vpnActivityIntent(context: Context): Intent =
    Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

/** Longest a check waits for a node or mode change to be applied. */
private const val ROUTING_WAIT_MS = 5_000L

object VpnRuntime {
    private val mutableState = MutableStateFlow(VpnUiState())
    val state = mutableState.asStateFlow()

    internal fun publish(value: VpnUiState) {
        mutableState.value = value
    }

    /** Routing changes asked of the service and not yet applied (or given up). */
    private val pendingRouting = MutableStateFlow(0)

    /** True when no node, mode or routing change is still being applied. */
    val routingSettled: Boolean
        get() = pendingRouting.value == 0

    /**
     * Saves a routing change with [save], then asks a running VPN to apply it; [awaitRouting] waits
     * for it. The change counts as pending before it is saved, so nothing that sees the new choice
     * can miss that it is not applied yet.
     */
    fun reconfigure(context: Context, save: () -> Unit = {}) {
        pendingRouting.update { it + 1 }
        var saved = false
        try {
            save()
            saved = true
        } finally {
            if (!saved) routingApplied()
        }
        runCatching {
                context.startService(
                    Intent(context, PingVpnService::class.java)
                        .setAction(PingVpnService.RECONFIGURE)
                )
            }
            .onFailure { routingApplied() }
    }

    internal fun routingApplied() {
        pendingRouting.update { (it - 1).coerceAtLeast(0) }
    }

    internal fun routingAbandoned() {
        pendingRouting.value = 0
    }

    /** Waits until requested routing changes are applied; never longer than [limitMillis]. */
    suspend fun awaitRouting(limitMillis: Long = ROUTING_WAIT_MS) {
        withTimeoutOrNull(limitMillis) { pendingRouting.first { it == 0 } }
    }

    internal fun stats(owner: PingVpnService, status: JSONObject, upSpeed: Long, downSpeed: Long) {
        mutableState.update {
            if (it.phase == "established" && PingVpnService.instance === owner)
                it.copy(
                    uploaded = status.optLong("uploaded"),
                    downloaded = status.optLong("downloaded"),
                    failures = status.optLong("failures"),
                    uploadSpeed = upSpeed,
                    downloadSpeed = downSpeed,
                    uptime = owner.sessionSeconds(),
                    connections = status.optInt("activeConnections"),
                    totalConnections = status.optLong("totalConnections"),
                    dnsQueries = status.optLong("dnsQueries"),
                    rejected = status.optLong("rejected"),
                    heapBytes = status.optLong("heapBytes"),
                    goroutines = status.optInt("goroutines"),
                    history = (it.history + SpeedSample(upSpeed, downSpeed)).takeLast(60),
                )
            else it
        }
    }

    fun connect(context: Context) {
        // A recreated/resumed UI is not the owner of the service lifetime.
        // Recover its projection before admitting another CONNECT command.
        if (synchronize()) return
        val previous = mutableState.value
        if (
            previous.active ||
                !mutableState.compareAndSet(
                    previous,
                    VpnUiState(VpnPhase.CONNECTING.wire, tr(R.string.starting_vpn)),
                )
        )
            return
        try {
            context.startForegroundService(
                Intent(context, PingVpnService::class.java).setAction(PingVpnService.CONNECT)
            )
        } catch (error: Exception) {
            publish(VpnUiState(VpnPhase.ERROR.wire, tr(R.string.android_did_not_allow_the_vpn_to)))
            throw error
        }
    }

    fun disconnect(context: Context) {
        if (state.value.active)
            context.startService(
                Intent(context, PingVpnService::class.java).setAction(PingVpnService.DISCONNECT)
            )
    }

    internal fun synchronize(): Boolean = PingVpnService.instance?.synchronizeState() == true

    internal fun recovering(owner: PingVpnService, started: Long, seconds: Long) {
        val current = mutableState.value
        if (PingVpnService.instance === owner && !current.active)
            mutableState.compareAndSet(
                current,
                VpnUiState(
                    VpnPhase.CONNECTING.wire,
                    tr(R.string.vpn_reconnecting),
                    uptime = seconds,
                    sessionStartedElapsed = started,
                ),
            )
    }
}

class PingVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stopRequested = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val synchronizing = AtomicBoolean(false)
    private val networkChanges = Channel<Unit>(Channel.CONFLATED)
    private var statisticsJob: Job? = null
    @Volatile private var terminalMessage: String? = null
    @Volatile private var terminalCode: String? = null
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile
    internal var boundNetwork: Network? = null
        private set

    private var observing = false
    @Volatile private var commandStartId = 0
    @Volatile private var attemptNumber = 0L
    @Volatile private var destroying = false
    @Volatile private var sessionStartedElapsed = -1L

    internal fun sessionSeconds() =
        if (sessionStartedElapsed < 0) 0
        else ((SystemClock.elapsedRealtime() - sessionStartedElapsed) / 1000).coerceAtLeast(0)

    private var usageStarted = false
    private var sessionCountRecorded = false
    private var appliedData: AppData? = null
    private val usageRecorded = AtomicBoolean(false)
    private val manager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val physicalNetworks =
        java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
    private val observer =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkChanges.trySend(Unit)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                physicalNetworks[network] = capabilities
                networkChanges.trySend(Unit)
            }

            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                networkChanges.trySend(Unit)
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL,
                        tr(R.string.vpn_connection),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { setShowBadge(false) }
            )
        scope.launch {
            for (ignored in networkChanges) {
                delay(300)
                mutex.withLock {
                    if (starting.get() && !stopRequested.get() && descriptor != null) {
                        try {
                            rebindNetwork()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            shutdown(tr(R.string.tun_forwarding_could_not_start_vpn_was))
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commandStartId = startId
        if (intent?.action == DISCONNECT) {
            requestStop()
            return START_NOT_STICKY
        }
        val settings = (application as PingClashApplication).repository.data.value.client
        if (intent?.action == RECONFIGURE) {
            return reconfigure(settings)
        }
        val resume = shouldResume(intent, settings)
        if (intent?.action != CONNECT && !isAlwaysOn && !resume) {
            requestStop()
            return START_NOT_STICKY
        }
        // The last returned value wins; a duplicate CONNECT must not drop restart.
        if (!starting.compareAndSet(false, true)) return restartPolicy(settings)
        val attempt = ++attemptNumber
        stopRequested.set(false)
        terminalMessage = null
        terminalCode = null
        getSharedPreferences("vpn_runtime", 0).edit().putBoolean("resume_requested", true).apply()
        try {
            val notification = notification(tr(R.string.establishing_vpn_tunnel))
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            getSharedPreferences("vpn_runtime", 0)
                .edit()
                .putBoolean("resume_requested", false)
                .apply()
            scope.launch {
                mutex.withLock {
                    shutdown(tr(R.string.android_did_not_allow_the_vpn_foreground), "foreground")
                }
            }
            return START_NOT_STICKY
        }
        VpnRuntime.publish(VpnUiState(VpnPhase.CONNECTING.wire, tr(R.string.establishing_tunnel)))
        scope.launch {
            mutex.withLock {
                if (attempt == attemptNumber) {
                    if (!stopRequested.get()) establish() else shutdown()
                }
            }
        }
        return restartPolicy(settings)
    }

    /** Reconcile only this owned service; a system VPN icon may belong to another app. */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // Safe JNI boundary; never publish native exception text.
    internal fun synchronizeState(): Boolean {
        if (destroying || stopRequested.get() || !starting.get()) return false
        VpnRuntime.recovering(this, sessionStartedElapsed, sessionSeconds())
        if (synchronizing.compareAndSet(false, true))
            scope.launch {
                try {
                    mutex.withLock {
                        if (instance === this@PingVpnService && !destroying && !stopRequested.get())
                            reconcileOwnedSession()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutex.withLock {
                        if (instance === this@PingVpnService && !destroying && !stopRequested.get())
                            shutdown(
                                tr(R.string.tun_forwarding_could_not_start_vpn_was),
                                "forwarder",
                            )
                    }
                } finally {
                    synchronizing.set(false)
                }
            }
        return true
    }

    private fun reconcileOwnedSession() {
        val status = JSONObject(Mobile.vpnStatus())
        if (descriptor != null && status.optBoolean("running")) {
            val current = VpnRuntime.state.value
            if (
                current.stage != VpnPhase.ESTABLISHED ||
                    current.sessionStartedElapsed != sessionStartedElapsed
            ) {
                VpnRuntime.publish(
                    current.copy(
                        phase = VpnPhase.ESTABLISHED.wire,
                        message = tr(R.string.vpn_tunnel_established),
                        uptime = sessionSeconds(),
                        sessionStartedElapsed = sessionStartedElapsed,
                        failureCode = null,
                        failureId = 0,
                    )
                )
            }
        } else if (boundNetwork == null) {
            VpnRuntime.publish(
                VpnUiState(
                    VpnPhase.CONNECTING.wire,
                    tr(R.string.vpn_waiting_network),
                    uptime = sessionSeconds(),
                    sessionStartedElapsed = sessionStartedElapsed,
                )
            )
        } else {
            shutdown(tr(R.string.tun_forwarding_could_not_start_vpn_was), "forwarder")
        }
    }

    private suspend fun establish() {
        var stage = "native"
        try {
            val app = application as PingClashApplication
            check(app.nativeReady) { "native_unavailable" }
            check(RuleData.prepare(app).get()) { "rule_data_unavailable" }
            stage = "permission"
            check(prepare(this) == null) { "vpn_permission_required" }
            app.repository.applyPendingProviders()
            val data = app.repository.data.value
            stage = "configuration"
            VpnConfigurationFailure.requireConnectable(data)
            val runtimeConfig = requireNotNull(data.selectedRuntimeConfig)
            VpnConfigurationFailure.validateNode(runtimeConfig)
            val options = VpnConfigurationFailure.runtimeOptions(data)
            stage = "network"
            val network = currentNetwork()
            boundNetwork = network
            if (descriptor != null)
                check(setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray()))
            if (!observing) {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(),
                    observer,
                )
                observing = true
            }
            if (stopRequested.get()) {
                shutdown()
                return
            }
            stage = "tun"
            val builder =
                Builder()
                    .setSession("PingClash")
                    .setMtu(data.client.mtu)
                    .setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray())
                    .addAddress("172.29.0.1", 30)
                    .addAddress("fdfe:7069:6e67::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("172.29.0.2")
                    // Since Android 10 a VPN is metered unless declared otherwise; the
                    // underlying network still decides metering for downloads and backups.
                    .setMetered(false)
            // Always capture both address families. Disabling IPv6 rejects it in
            // the forwarder rather than allowing it to escape the VPN route.
            // Only the core's outbound sockets bypass the TUN through Platform.protect.
            // App HTTPS (including website checks) must follow the same VPN route as other apps.
            stage = "applications"
            // Uninstalled or invisible packages are skipped instead of failing the
            // whole VPN; the established notice reports how many were ignored.
            var skippedApplications = 0
            fun applyPackage(name: String, add: (String) -> Unit) {
                try {
                    add(name)
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    skippedApplications++
                }
            }
            if (data.client.appMode == "include")
                (data.client.packages + packageName).distinct().forEach {
                    applyPackage(it) { name -> builder.addAllowedApplication(name) }
                }
            else if (data.client.appMode == "exclude")
                data.client.packages
                    .filterNot { it == packageName }
                    .forEach { applyPackage(it) { name -> builder.addDisallowedApplication(name) } }
            stage = "tun"
            if (descriptor == null) descriptor = builder.establish() ?: error("tun_unavailable")
            if (network == null) {
                if (!data.client.autoReconnect) error("network_unavailable")
                VpnRuntime.publish(
                    VpnUiState(
                        VpnPhase.CONNECTING.wire,
                        tr(R.string.vpn_waiting_network),
                        uptime = sessionSeconds(),
                        sessionStartedElapsed = sessionStartedElapsed,
                    )
                )
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(tr(R.string.vpn_waiting_network)))
                return
            }
            val result =
                JSONObject(
                    Mobile.startVPNWithOptions(
                        runtimeConfig,
                        requireNotNull(descriptor).fd.toLong(),
                        data.dnsAddress,
                        options,
                    )
                )
            val startupCode = result.optString("code")
            VpnConfigurationFailure.checkNativeCode(startupCode)
            stage =
                when (startupCode) {
                    "config_or_dns_invalid" -> "configuration"
                    "vpn_already_running" -> "core_busy"
                    "tun_fd_invalid" -> "tun"
                    else -> "forwarder"
                }
            check(result.optString("code") == "tun_established_not_verified") {
                "native_start_failed"
            }
            if (stopRequested.get()) {
                shutdown()
                return
            }
            if (sessionStartedElapsed < 0) sessionStartedElapsed = SystemClock.elapsedRealtime()
            VpnRuntime.publish(
                VpnUiState(
                    VpnPhase.ESTABLISHED.wire,
                    if (skippedApplications == 0) tr(R.string.vpn_tunnel_established)
                    else tr(R.string.vpn_applications_skipped, skippedApplications),
                    uptime = sessionSeconds(),
                    sessionStartedElapsed = sessionStartedElapsed,
                )
            )
            usageStarted = true
            usageRecorded.set(false)
            appliedData = data
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    notification(tr(R.string.vpn_tunnel_established_tap_to_view)),
                )
            statisticsJob =
                scope.launch {
                    var previousTime = SystemClock.elapsedRealtime()
                    // -1 until the first reading: the counters already hold the session's
                    // bytes, which are not one second of speed (that spike flattened the chart).
                    var previousUp = -1L
                    var previousDown = -1L
                    var postedText = tr(R.string.vpn_tunnel_established_tap_to_view)
                    val power = getSystemService(android.os.PowerManager::class.java)
                    while (
                        !stopRequested.get() && !destroying && instance === this@PingVpnService
                    ) {
                        delay(1000)
                        runCatching {
                            val status = JSONObject(Mobile.vpnStatus())
                            if (status.optBoolean("running")) {
                                val now = SystemClock.elapsedRealtime()
                                val duration = (now - previousTime).coerceAtLeast(1)
                                if (previousUp < 0) {
                                    previousUp = status.optLong("uploaded")
                                    previousDown = status.optLong("downloaded")
                                }
                                val up =
                                    ((status.optLong("uploaded") - previousUp).coerceAtLeast(0) *
                                        1000 / duration)
                                val down =
                                    ((status.optLong("downloaded") - previousDown).coerceAtLeast(
                                        0
                                    ) * 1000 / duration)
                                VpnRuntime.stats(this@PingVpnService, status, up, down)
                                previousTime = now
                                previousUp = status.optLong("uploaded")
                                previousDown = status.optLong("downloaded")
                                val text =
                                    if (app.repository.data.value.client.notificationSpeed)
                                        "↑ ${formatBytes(up)}/s   ↓ ${formatBytes(down)}/s"
                                    else tr(R.string.vpn_tunnel_established_tap_to_view)
                                // Re-post only on change, and skip speed ticks nobody can see.
                                // The title shows the mode, so a mode change counts as well.
                                val posting = "${notificationTitle()}|$text"
                                if (posting != postedText && power.isInteractive) {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, notification(text))
                                    postedText = posting
                                }
                            }
                        }
                    }
                }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: VpnConfigurationFailure) {
            shutdown(failure.publicMessage(), failure.code)
        } catch (_: Exception) {
            val message =
                when (stage) {
                    "native" -> tr(R.string.the_native_core_could_not_load_vpn)
                    "permission" -> tr(R.string.vpn_permission_is_incomplete_connect_and_g)
                    "configuration" -> tr(R.string.proxy_or_dns_configuration_failed_validati)
                    "network" -> tr(R.string.no_usable_non_vpn_network_or_network)
                    "applications" -> tr(R.string.vpn_application_unavailable)
                    "core_busy" -> tr(R.string.vpn_core_busy)
                    "tun" -> tr(R.string.android_could_not_establish_tun_vpn_was)
                    else -> tr(R.string.tun_forwarding_could_not_start_vpn_was)
                }
            shutdown(message, stage)
        }
    }

    private fun currentNetwork(): Network? {
        fun eligible(network: Network) =
            manager.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        val active = manager.activeNetwork
        // Once our UID joins the TUN, activeNetwork is our VPN. Track physical
        // transports explicitly for protected core sockets and network handoff only.
        return active?.takeIf(::eligible)
            ?: if (descriptor == null) null
            else
                physicalNetworks.keys.filter(::eligible).maxByOrNull { network ->
                    val caps = manager.getNetworkCapabilities(network)
                    (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                        VALIDATED_PRIORITY
                    else 0) +
                        (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                            ETHERNET_PRIORITY
                        else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                            WIFI_PRIORITY
                        else CELLULAR_PRIORITY)
                }
    }

    private suspend fun rebindNetwork(force: Boolean = false) {
        val next = currentNetwork()
        val running =
            runCatching { JSONObject(Mobile.vpnStatus()).optBoolean("running") }.getOrDefault(false)
        val unchanged = next == boundNetwork && (running || next == null)
        if (!force && unchanged) return
        if (
            !force &&
                !(application as PingClashApplication).repository.data.value.client.autoReconnect
        )
            shutdown(tr(R.string.network_changed_vpn_disconnected_reconnect))
        else reestablish(next)
    }

    private suspend fun reestablish(next: Network?) {
        statisticsJob?.cancel()
        statisticsJob = null
        saveUsage()
        usageStarted = false
        // Stop old outbound sockets before installing the new physical route.
        // The original TUN descriptor remains open throughout the transition.
        boundNetwork = null
        check(setUnderlyingNetworks(emptyArray()))
        Mobile.stopVPN()
        if (stopRequested.get()) {
            shutdown()
            return
        }
        val message =
            tr(if (next == null) R.string.vpn_waiting_network else R.string.vpn_reconnecting)
        VpnRuntime.publish(
            VpnUiState(
                VpnPhase.CONNECTING.wire,
                message,
                uptime = sessionSeconds(),
                sessionStartedElapsed = sessionStartedElapsed,
            )
        )
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
        establish()
    }

    private fun restartPolicy(settings: ClientSettings) =
        if (isAlwaysOn || settings.autoReconnect) START_STICKY else START_NOT_STICKY

    private fun shouldResume(intent: Intent?, settings: ClientSettings) =
        intent == null &&
            settings.autoReconnect &&
            getSharedPreferences("vpn_runtime", 0).getBoolean("resume_requested", false) &&
            prepare(this) == null

    @Suppress(
        "TooGenericExceptionCaught"
    ) // JNI/config errors must preserve the last working tunnel.
    private fun reconfigure(settings: ClientSettings): Int {
        // Counted down however the job ends: applied, failed, skipped or cancelled.
        val job =
            scope.launch {
                mutex.withLock {
                    if (descriptor != null && !stopRequested.get()) {
                        val attempted = (application as PingClashApplication).repository.data.value
                        try {
                            val repository = (application as PingClashApplication).repository
                            val data = attempted
                            val config = requireNotNull(data.selectedRuntimeConfig)
                            // The core validates a replacement before publishing it, and can apply
                            // mode/group-only changes without reconstructing the accepted graph.
                            val options =
                                VpnConfigurationFailure.runtimeOptions(data, validateCore = false)
                            val result =
                                JSONObject(
                                    Mobile.updateVPNWithOptions(config, data.dnsAddress, options)
                                )
                            if (result.optString("code") != "vpn_routing_updated") {
                                // Detailed diagnostics are needed only on failure, with the
                                // previous
                                // native generation still serving traffic throughout validation.
                                VpnConfigurationFailure.validateNode(config)
                                VpnConfigurationFailure.runtimeOptions(data)
                                VpnConfigurationFailure.checkNativeCode(result.optString("code"))
                                error("routing_update_failed")
                            }
                            appliedData = data
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val repository = (application as PingClashApplication).repository
                            appliedData?.let { repository.restoreRouting(it, attempted) }
                            val text =
                                (failure as? VpnConfigurationFailure)?.publicMessage()
                                    ?: tr(R.string.vpn_update_failed)
                            VpnRuntime.publish(
                                VpnRuntime.state.value.copy(
                                    message = text,
                                    failureCode = "configuration",
                                    failureId = SystemClock.elapsedRealtimeNanos(),
                                )
                            )
                        }
                    }
                }
            }
        job.invokeOnCompletion { VpnRuntime.routingApplied() }
        return restartPolicy(settings)
    }

    private fun requestStop(message: String? = null) {
        stopRequested.set(true)
        getSharedPreferences("vpn_runtime", 0).edit().putBoolean("resume_requested", false).apply()
        val attempt = attemptNumber
        scope.launch { mutex.withLock { if (attempt == attemptNumber) shutdown(message) } }
    }

    private fun shutdown(message: String? = null, code: String? = null) {
        stopRequested.set(true)
        val endingAttempt = attemptNumber
        if (message != null) {
            terminalMessage = message
            terminalCode = code
        }
        saveUsage()
        sessionStartedElapsed = -1
        statisticsJob?.cancel()
        statisticsJob = null
        getSharedPreferences("vpn_runtime", 0).edit().putBoolean("resume_requested", false).apply()
        VpnRuntime.publish(
            VpnUiState(VpnPhase.STOPPING.wire, tr(R.string.cleaning_up_vpn_connection))
        )
        runCatching { Mobile.stopVPN() }
        runCatching { descriptor?.close() }
        descriptor = null
        if (observing) {
            runCatching { manager.unregisterNetworkCallback(observer) }
            observing = false
        }
        boundNetwork = null
        physicalNetworks.clear()
        runCatching { setUnderlyingNetworks(null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Keep the UI stopping until Android completes onDestroy. Publishing
        // error/idle before then admits a retry into a Service already scheduled
        // for destruction, and that destruction can cancel the new attempt.
        android.os.Handler(mainLooper).post {
            if (!destroying && endingAttempt == attemptNumber && stopRequested.get()) {
                stopSelfResult(commandStartId)
            }
        }
    }

    override fun onRevoke() {
        terminalMessage = tr(R.string.vpn_permission_revoked_connection_cleaned)
        requestStop(terminalMessage)
        super.onRevoke()
    }

    override fun onDestroy() {
        destroying = true
        VpnRuntime.routingAbandoned()
        stopRequested.set(true)
        saveUsage()
        runCatching { Mobile.stopVPN() }
        runCatching { descriptor?.close() }
        descriptor = null
        if (observing) runCatching { manager.unregisterNetworkCallback(observer) }
        boundNetwork = null
        if (instance === this) {
            instance = null
            starting.set(false)
            VpnRuntime.publish(
                terminalMessage?.let {
                    VpnUiState(
                        VpnPhase.ERROR.wire,
                        it,
                        failureCode = terminalCode,
                        failureId = SystemClock.elapsedRealtimeNanos(),
                    )
                } ?: VpnUiState()
            )
        }
        scope.cancel()
        networkChanges.close()
        super.onDestroy()
    }

    private fun saveUsage() {
        if (!usageStarted || !usageRecorded.compareAndSet(false, true)) return
        runCatching {
            val status = JSONObject(Mobile.vpnStatus())
            (application as PingClashApplication)
                .repository
                .recordUsage(
                    status.optLong("uploaded"),
                    status.optLong("downloaded"),
                    status.optLong("uptime"),
                    !sessionCountRecorded,
                )
            sessionCountRecorded = true
        }
    }

    /** "PingClash · Rules": the mode in the title, shown as status only. */
    private fun notificationTitle(): String {
        val mode = (application as PingClashApplication).repository.data.value.client.mode
        val label = HomeModes.firstOrNull { it.first == mode }?.second ?: R.string.rules
        return "PingClash · ${tr(label)}"
    }

    private fun notification(message: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                vpnActivityIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, PingVpnService::class.java).setAction(DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(dev.pingclash.app.R.drawable.ic_status)
            .setContentTitle(notificationTitle())
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 31)
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .addAction(Notification.Action.Builder(null, tr(R.string.disconnect_2), stop).build())
            .build()
    }

    companion object {
        private const val VALIDATED_PRIORITY = 100
        private const val ETHERNET_PRIORITY = 30
        private const val WIFI_PRIORITY = 20
        private const val CELLULAR_PRIORITY = 10
        const val CONNECT = "dev.pingclash.CONNECT"
        const val RECONFIGURE = "dev.pingclash.RECONFIGURE"
        const val DISCONNECT = "dev.pingclash.DISCONNECT"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        @Volatile
        internal var instance: PingVpnService? = null
            private set
    }
}
