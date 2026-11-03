// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * The address the internet sees from this phone right now: through the VPN with its current mode
 * and rules when connected, the phone's own connection otherwise. Held in memory only.
 */
internal data class ExitInfo(
    /** The [exitRoute] it was measured on; a result for another route is not shown. */
    val route: String,
    val ip: String,
    val country: String,
    val asn: Int,
    val org: String,
    /** The rule's outbound: "DIRECT", a group name, "PROXY" (the main proxy) or empty. */
    val policy: String,
    val node: String,
    val type: String,
    val checkedAt: Long,
) {
    override fun toString() = "ExitInfo(redacted)"

    companion object {
        /** The core's reading of a trace, or null when it did not pass. */
        fun read(reply: JSONObject, route: String, main: LocalNode?, now: Long): ExitInfo? {
            if (reply.optString("state") != "passed" || reply.optString("ip").isBlank()) return null
            val policy = reply.optString("policy")
            // A plain main node is parsed under a placeholder; its real name is ours.
            val viaMain = policy == "PROXY" && reply.optString("node").isBlank()
            return ExitInfo(
                route,
                reply.optString("ip"),
                reply.optString("country"),
                reply.optInt("asn"),
                reply.optString("org"),
                policy,
                if (viaMain) main?.name.orEmpty() else reply.optString("node"),
                if (viaMain) main?.type.orEmpty() else reply.optString("type"),
                now,
            )
        }
    }
}

internal data class ExitState(
    val info: ExitInfo? = null,
    val checking: Boolean = false,
    val failed: Boolean = false,
)

/**
 * What the current address depends on: the VPN session, mode, profile and main proxy, or the
 * phone's own connection. Null while the VPN is starting or stopping.
 */
internal fun exitRoute(vpn: VpnUiState, data: AppData): String? =
    when (vpn.stage) {
        VpnPhase.ESTABLISHED ->
            "vpn:${vpn.sessionStartedElapsed}:${data.client.mode}:" +
                "${data.activeProfile?.id}:${data.selectedId}"
        VpnPhase.IDLE,
        VpnPhase.ERROR -> "direct"
        else -> null
    }

/**
 * Fetches Cloudflare's trace like any other app on the phone would, so the reply shows the route
 * traffic really takes. No redirects, no cache, a small bounded body.
 */
internal fun fetchExitTrace(timeoutMillis: Int): String {
    val connection = URL(EXIT_TRACE_URL).openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.setRequestProperty("User-Agent", "PingClash")
        connection.setRequestProperty("Accept-Encoding", "identity")
        check(connection.responseCode == HttpsURLConnection.HTTP_OK) { "exit_http_status" }
        return connection.inputStream.use(::readTrace)
    } finally {
        connection.disconnect()
    }
}

private fun readTrace(input: java.io.InputStream): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(MAX_TRACE_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        check(out.size() + count <= MAX_TRACE_BYTES) { "exit_reply_too_large" }
        out.write(buffer, 0, count)
    }
    return out.toString(Charsets.UTF_8.name())
}

/** "🇭🇰 Hong Kong" in the app's language; empty when the code is not a country. */
internal fun exitCountryLabel(code: String, language: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
    val name = Locale("", code).getDisplayCountry(Locale.forLanguageTag(language))
    val flag =
        code.map { String(Character.toChars(REGIONAL_INDICATOR_A + (it - 'A'))) }.joinToString("")
    return if (name.isBlank() || name == code) flag else "$flag $name"
}

/** Mihomo adapter names ("Vless", "Shadowsocks") in the labels used elsewhere in the app. */
internal fun exitProtocolLabel(type: String): String =
    when (val kind = type.lowercase(Locale.ROOT)) {
        "shadowsocks" -> proxyTypeLabel("ss")
        "socks5" -> proxyTypeLabel("socks5")
        else -> proxyTypeLabel(kind).takeIf { it != kind } ?: type
    }

/** The route row: "Direct", or the rule's group, the node and its protocol. */
internal fun exitRouteLabel(info: ExitInfo): String =
    if (info.policy == "DIRECT") tr(R.string.direct)
    else
        listOf(
                info.policy.takeUnless { it == "PROXY" || it == info.node }.orEmpty(),
                info.node,
                exitProtocolLabel(info.type),
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")

private const val EXIT_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
private const val MAX_TRACE_BYTES = 4096
private const val REGIONAL_INDICATOR_A = 0x1F1E6
