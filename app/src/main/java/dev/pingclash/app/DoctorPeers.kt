// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // DNS wire format and the doctor's fixed limits.

package dev.pingclash.app

import android.content.Context
import android.net.Network
import dev.pingclash.mobile.Mobile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How many other nodes the doctor compares the selected one with. */
internal const val DOCTOR_PEERS = 3

/** One other node's result: it worked (with its time) or failed (with the core's code). */
internal data class PeerResult(val name: String, val ok: Boolean, val ms: Long, val code: String)

/** What the two DNS lookups of the node's server said. */
internal enum class DnsVerdict {
    /** The node is given as an IP address: nothing to look up. */
    LITERAL,
    /** Both answered and share an address. */
    SAME,
    /** Both answered with different addresses: normal for a server behind a CDN. */
    DIFFERENT,
    /**
     * The network answers with a private or reserved address where the reference has a public one.
     */
    TAMPERED,
    /** The lookups could not be compared (both failed, or no network). */
    UNKNOWN,
}

internal data class DnsCheck(
    val verdict: DnsVerdict,
    val system: List<String> = emptyList(),
    val configured: List<String> = emptyList(),
)

/**
 * Up to [DOCTOR_PEERS] other nodes of the active profile, spread over its list (providers list
 * nodes by region, so the first, middle and last are usually in different places).
 */
internal fun peerCandidates(data: AppData): List<LocalNode> {
    val selected = data.selected?.id
    val nodes = data.activeProfile?.nodes.orEmpty().filter { it.id != selected }
    if (nodes.size <= DOCTOR_PEERS) return nodes
    return listOf(0, nodes.size / 2, nodes.size - 1).distinct().map(nodes::get)
}

/** Each candidate through the core, together, over the same test address the diagnosis uses. */
internal suspend fun probePeers(data: AppData, timeoutMs: Int): List<PeerResult> {
    val profile = data.activeProfile ?: return emptyList()
    return coroutineScope {
        peerCandidates(data)
            .map { node ->
                async(Dispatchers.IO) {
                    val config =
                        if (node.requiresProfile) profile.runtimeConfig(node.name) else node.config
                    val run = UUID.randomUUID().toString()
                    try {
                        val reply =
                            JSONObject(Mobile.probe(config, data.probeUrl, run, timeoutMs.toLong()))
                        PeerResult(
                            node.name,
                            reply.optString("state") == "passed",
                            reply.optLong("elapsedMs"),
                            reply.optString("code"),
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        Mobile.cancel(run)
                        throw cancelled
                    } catch (_: Exception) {
                        PeerResult(node.name, false, 0, "internal_error")
                    }
                }
            }
            .awaitAll()
    }
}

/**
 * The node's server name looked up twice outside the VPN: by the phone's network, and by the DNS
 * set in Settings (which the app uses anyway): its encrypted DNS first, so a network that rewrites
 * plain DNS cannot answer both, then the plain DNS server. No other server is asked.
 */
internal suspend fun dnsCheck(
    context: Context,
    node: LocalNode?,
    dnsAddress: String,
    dnsHttps: String,
): DnsCheck =
    withContext(Dispatchers.IO) {
        val host =
            node?.let { runCatching { JSONObject(it.config).optString("server") }.getOrNull() }
        if (host.isNullOrBlank()) return@withContext DnsCheck(DnsVerdict.UNKNOWN)
        if (android.net.InetAddresses.isNumericAddress(host))
            return@withContext DnsCheck(DnsVerdict.LITERAL)
        val network = physicalNetwork(context) ?: return@withContext DnsCheck(DnsVerdict.UNKNOWN)
        val system =
            runCatching { network.getAllByName(host).map { it.hostAddress.orEmpty() } }
                .getOrDefault(emptyList())
        val configured =
            dnsHttps
                .takeIf { it.isNotBlank() }
                ?.let { url -> runCatching { queryHttps(network, host, url) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching { queryServer(network, host, dnsAddress) }.getOrDefault(emptyList())
        DnsCheck(compareDns(system, configured), system, configured)
    }

/** The physical network the core uses: the VPN's bound network, or the system's default one. */
internal fun physicalNetwork(context: Context): Network? =
    (context.applicationContext as? android.app.Application)?.let {
        AndroidCorePlatform(it).underlying()
    }
