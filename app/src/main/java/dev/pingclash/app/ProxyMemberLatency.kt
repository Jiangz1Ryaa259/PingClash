// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ProxyMemberLatency(name: String, delay: ProxyDelay?, test: (() -> Unit)?) {
    Box(
        Modifier.widthIn(min = 44.dp, max = 112.dp)
            .heightIn(min = 32.dp)
            .testTag("proxy-latency-$name")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = test != null,
            ) {
                test?.invoke()
            }
            .semantics { contentDescription = tr(R.string.test, name) },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (delay?.state in setOf("queued", "running", "members_running")) {
            CircularProgressIndicator(
                Modifier.size(15.dp).testTag("proxy-pending-$name"),
                strokeWidth = 1.5.dp,
            )
        } else if (delay == null && test != null) ProxySymbol("bolt", Modifier.size(17.dp), Mint)
        else {
            val color =
                when (delay?.state) {
                    "passed" -> latencyColor(delay.elapsedMs)
                    "timeout",
                    "failed" -> MaterialTheme.colorScheme.error
                    else -> Muted
                }
            Text(
                proxyLatencyText(delay),
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
