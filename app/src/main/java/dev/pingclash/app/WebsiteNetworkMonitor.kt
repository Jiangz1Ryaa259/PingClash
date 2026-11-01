// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable

internal data class WebsiteRoute(val network: Network, val vpn: Boolean)

/** Observe only the application's default route. Never choose an underlying physical network. */
internal class WebsiteNetworkMonitor(context: Context, private val changed: () -> Unit) :
    Closeable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var observed: Network? = manager.activeNetwork
    private var vpn: Boolean? = null
    private var links: LinkProperties? = null
    private var registered = false
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (observed != network) {
                    observed = network
                    vpn = null
                    links = null
                    changed()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (network != observed) return
                val next = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (vpn != null && vpn != next) changed()
                vpn = next
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network != observed) return
                if (links != null && links != properties) changed()
                links = properties
            }

            override fun onLost(network: Network) {
                if (network == observed) {
                    observed = null
                    vpn = null
                    links = null
                    changed()
                }
            }
        }

    init {
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    fun current(): WebsiteRoute? {
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)?.let {
            WebsiteRoute(network, it.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        }
    }

    override fun close() {
        if (registered) {
            runCatching { manager.unregisterNetworkCallback(callback) }
            registered = false
        }
    }
}
