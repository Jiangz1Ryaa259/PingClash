// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

internal fun proxyLatencyText(delay: ProxyDelay?): String =
    when (delay?.state) {
        "passed" -> "${delay.elapsedMs.coerceAtLeast(1)} ms"
        "members" -> groupDelayText(delay)
        "timeout" -> tr(R.string.timeout)
        "failed",
        "dns",
        "stale" -> tr(R.string.failed)
        "busy" -> tr(R.string.try_again_shortly)
        "running",
        "queued",
        "members_running" -> "…"
        else -> tr(R.string.ui_not_measured)
    }

internal fun tcpDetailText(udpOnly: Boolean, tcp: ProxyDelay?): String =
    when {
        udpOnly -> tr(R.string.latency_udp_na)
        tcp?.state == "skipped" -> tr(R.string.latency_skipped)
        else -> proxyLatencyText(tcp)
    }
