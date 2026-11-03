// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val TileShape = RoundedCornerShape(16.dp)

/** Live session time from the monotonic start, so a reconnect does not reset it. */
internal fun sessionSeconds(vpn: VpnUiState, now: Long): Long =
    when {
        !vpn.active -> 0
        vpn.sessionStartedElapsed >= 0 ->
            ((now - vpn.sessionStartedElapsed) / 1000).coerceAtLeast(0)
        else -> vpn.uptime.coerceAtLeast(0)
    }

private data class Metric(val icon: PcIcon, val label: String, val value: String)

/**
 * The home's dashboard, after FlClash's and sing-box's: a speed panel with a live chart, then every
 * session metric as a tile and the all-time usage (the subscription has its own page). Nothing to
 * choose; it all shows. Scrolls on its own; the home never does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDashboardSheet(
    state: DashboardState,
    onRefreshExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .testTag("home-details"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardHeader(state)
            ExitSection(state, onRefreshExit)
            if (state.vpn.active) {
                SpeedPanel(state.vpn)
                MetricGrid(sessionMetrics(state.vpn))
            }
            if (state.data.client.keepUsage) {
                SectionTitle(tr(R.string.usage))
                MetricGrid(usageMetrics(state.data.usage))
            }
        }
    }
}

@Composable
private fun DashboardHeader(state: DashboardState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            state.data.activeProfile?.name ?: tr(R.string.ui_choose_configuration),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(lanyardPhaseText(state.vpn.phase), phaseColor(state.vpn.phase))
            Chip(modeLabel(state.data.client.mode), null)
            state.data.activeProfile?.let { profile ->
                Chip("${tr(R.string.proxy_groups)} ${profile.groups.size}", null)
            }
        }
    }
}

@Composable
internal fun phaseColor(phase: String): Color =
    when (phase) {
        "established" -> Success
        "connecting",
        "stopping" -> Warning
        "error" -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9CA3AF)
    }

@Composable
private fun Chip(text: String, dot: Color?) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun sessionMetrics(vpn: VpnUiState) =
    listOf(
        Metric(
            PcIcon.Bolt,
            tr(R.string.duration),
            durationText(sessionSeconds(vpn, android.os.SystemClock.elapsedRealtime())),
        ),
        Metric(
            PcIcon.Link,
            tr(R.string.active_connections),
            "${vpn.connections} / ${vpn.totalConnections}",
        ),
        Metric(PcIcon.Download, tr(R.string.downloaded), formatBytes(vpn.downloaded)),
        Metric(PcIcon.Chart, tr(R.string.uploaded), formatBytes(vpn.uploaded)),
        Metric(PcIcon.Globe, tr(R.string.dns_queries), vpn.dnsQueries.toString()),
        Metric(PcIcon.Shield, tr(R.string.policy_blocks), vpn.rejected.toString()),
        Metric(PcIcon.Info, tr(R.string.forwarding_failures), vpn.failures.toString()),
        Metric(PcIcon.Layers, tr(R.string.memory), formatBytes(vpn.heapBytes)),
    )

@Composable
private fun usageMetrics(usage: LocalUsage) =
    listOf(
        Metric(PcIcon.Download, tr(R.string.total_downloaded), formatBytes(usage.downloaded)),
        Metric(PcIcon.Chart, tr(R.string.total_uploaded), formatBytes(usage.uploaded)),
        Metric(PcIcon.Bolt, tr(R.string.total_duration), durationText(usage.seconds)),
        Metric(PcIcon.Power, tr(R.string.total_sessions), usage.sessions.toString()),
    )

@Composable
private fun MetricGrid(metrics: List<Metric>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { MetricTile(it, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(metric: Metric, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), TileShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(metric.icon, Modifier.padding(end = 6.dp).size(14.dp), Muted)
            Text(
                metric.label,
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            metric.value,
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
