// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** What the current network means for the Wi-Fi rule. */
internal enum class WifiRule {
    /** On a Wi-Fi network the user trusts: stay direct. */
    TRUSTED,
    /** On any other Wi-Fi network or on mobile data: connect. */
    OTHER,
    OFFLINE,
    /** On Wi-Fi, but Android does not tell which network (no location access). */
    UNKNOWN,
}

/**
 * Connects on untrusted networks and disconnects on trusted Wi-Fi. It acts only when the kind of
 * network changes, so a manual connect or disconnect stays until the next change. The network name
 * is read on the device for this rule only; it is never stored outside the settings or sent.
 */
internal object WifiAutoConnect {
    const val ACTION = "dev.pingclash.app.NETWORK_CHANGED"
    private const val PREFS = "automation"
    private const val LAST_RULE = "wifi_rule"
    private const val SETTLE_MS = 1500L
    private const val UNKNOWN_SSID = "<unknown ssid>"

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null
    private val settled = Runnable { appContext?.let { thread(name = "wifi-rule") { apply(it) } } }

    /** Follows network changes while [enabled]; also wakes the app for them while it is closed. */
    @Synchronized
    fun watch(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        appContext = app
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
        val wake =
            PendingIntent.getBroadcast(
                app,
                0,
                Intent(app, NetworkChangeReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        runCatching { manager.unregisterNetworkCallback(wake) }
        if (!enabled) {
            // Turning the rule on again applies it at once.
            app.getSharedPreferences(PREFS, 0).edit().remove(LAST_RULE).apply()
            return
        }
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        runCatching { manager.registerNetworkCallback(request, wake) }
        callback =
            object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = settle()

                    override fun onLost(network: Network) = settle()
                }
                .also { runCatching { manager.registerNetworkCallback(request, it) } }
        settle()
    }

    /** Applies the rule again soon, e.g. after the trusted list changed. */
    fun settle() {
        handler.removeCallbacks(settled)
        handler.postDelayed(settled, SETTLE_MS)
    }

    /** For a wake-up broadcast: wait for the network to settle, then apply the rule. */
    fun settleAndApply(context: Context) {
        Thread.sleep(SETTLE_MS)
        apply(context)
    }

    @Synchronized
    fun apply(context: Context) {
        val app = context.applicationContext as PingClashApplication
        val settings = app.repository.data.value.client
        val rule =
            if (settings.wifiAuto) currentRule(app, settings.trustedWifi) else WifiRule.UNKNOWN
        val prefs = app.getSharedPreferences(PREFS, 0)
        // Act only on a change between trusted Wi-Fi and other networks.
        if (
            rule == WifiRule.OFFLINE ||
                rule == WifiRule.UNKNOWN ||
                prefs.getString(LAST_RULE, null) == rule.name
        )
            return
        prefs.edit().putString(LAST_RULE, rule.name).apply()
        if (rule == WifiRule.TRUSTED) VpnShortcuts.stop(app) else VpnShortcuts.start(app)
    }

    fun currentRule(
        context: Context,
        trusted: List<String> =
            (context.applicationContext as PingClashApplication)
                .repository
                .data
                .value
                .client
                .trustedWifi,
    ): WifiRule {
        val networks = physicalNetworks(context)
        return when {
            networks.isEmpty() -> WifiRule.OFFLINE
            networks.none { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> WifiRule.OTHER
            else ->
                when (currentWifiName(context)) {
                    null -> WifiRule.UNKNOWN
                    in trusted -> WifiRule.TRUSTED
                    else -> WifiRule.OTHER
                }
        }
    }

    /** The Wi-Fi network's name, or null off Wi-Fi or when Android withholds it. */
    @Suppress("DEPRECATION") // The replacement needs a callback per read; this is one query.
    fun currentWifiName(context: Context): String? {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val ssid = runCatching { manager?.connectionInfo?.ssid }.getOrNull() ?: return null
        return ssid.removeSurrounding("\"").takeIf { it != UNKNOWN_SSID && validWifiName(it) }
    }

    /** Fine location for the name, and "all the time" for rules while the app is closed. */
    fun locationAllowed(context: Context, background: Boolean = true): Boolean {
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (!background || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    fun locationOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    @Suppress("DEPRECATION") // allNetworks: also the networks under our own VPN.
    private fun physicalNetworks(context: Context): List<NetworkCapabilities> {
        val manager =
            context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
                manager.allNetworks.mapNotNull(manager::getNetworkCapabilities).filter {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }
            .getOrDefault(emptyList())
    }
}
