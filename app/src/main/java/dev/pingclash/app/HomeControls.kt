// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun modeLabel(mode: String): String =
    tr(HomeModes.firstOrNull { it.first == mode }?.second ?: R.string.rules)

/**
 * The home's configuration row: the mode dial on the left and a short summary on the right. Tapping
 * the entire summary opens live traffic and latency details.
 */
@Composable
internal fun HomeConfiguration(state: DashboardState, actions: DashboardActions) {
    var details by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy && state.vpn.phase !in listOf("connecting", "stopping")
    Surface(
        shape = UiCorners.Card,
        color = Panel.copy(alpha = .86f),
        border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("home-configuration"),
    ) {
        BoxWithConstraints {
            val summary = Modifier.testTag("home-summary")
            if (maxWidth < 300.dp)
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ModeDial(state.data.client.mode, enabled, actions.mode)
                    HomeSummary(state, summary.fillMaxWidth()) { details = true }
                }
            else
                Row(
                    Modifier.padding(end = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeDial(state.data.client.mode, enabled, actions.mode)
                    HomeSummary(state, summary.weight(1f)) { details = true }
                }
        }
    }
    if (details) HomeDashboardSheet(state) { details = false }
}

@Composable
private fun HomeSummary(state: DashboardState, modifier: Modifier, onDetails: () -> Unit) {
    Column(
        modifier
            .clip(UiCorners.Card)
            .fluidClickable(depth = .97f, onClick = onDetails)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            state.data.activeProfile?.name ?: tr(R.string.ui_choose_configuration),
            Modifier.testTag("home-profile-name"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            modeLabel(state.data.client.mode),
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SummaryLines(state)
            }
            AppGlyph(PcIcon.ChevronRight, Modifier.size(14.dp), Muted)
        }
    }
}

/** Two short lines: the status, then live speed while connected or the remaining quota. */
@Composable
private fun SummaryLines(state: DashboardState) {
    val vpn = state.vpn
    StatusDotLine(vpn.phase, lanyardPhaseText(vpn.phase))
    val line =
        if (vpn.active)
            "↓ ${formatBytes(vpn.downloadSpeed)}/s · ↑ ${formatBytes(vpn.uploadSpeed)}/s"
        else remainingQuota(state.data.activeProfile)
    if (line != null)
        Text(
            line,
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
}

@Composable
private fun remainingQuota(profile: LocalProfile?): String? {
    val quota = profile?.quota
    val total = quota?.total
    val used = quota?.used
    return if (total == null || used == null) null
    else tr(R.string.subscription_remaining, formatBytes((total - used).coerceAtLeast(0)))
}

@Composable
private fun StatusDotLine(phase: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 6.dp).size(7.dp).background(phaseColor(phase), CircleShape))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
