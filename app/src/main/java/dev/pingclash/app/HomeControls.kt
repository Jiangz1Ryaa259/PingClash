// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
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
 * The home's configuration card: the mode drum across the top, then a summary of the profile, the
 * node and where traffic leaves (tap it for live traffic and latency), and, when [websites] is
 * given, the website checks docked at the bottom.
 */
@Composable
internal fun HomeConfiguration(
    state: DashboardState,
    actions: DashboardActions,
    websites: (@Composable () -> Unit)? = null,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy && state.vpn.phase !in listOf("connecting", "stopping")
    Surface(
        shape = UiCorners.Card,
        color = Panel.copy(alpha = .86f),
        border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("home-configuration"),
    ) {
        Column {
            ModeDrum(state.data.client.mode, enabled, actions.mode)
            HomeSummary(state, Modifier.fillMaxWidth().testTag("home-summary")) { details = true }
            if (websites != null) {
                HorizontalDivider(
                    thickness = .5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                websites()
            }
        }
    }
    ExitRefreshEffect(state, actions.refreshExit)
    if (details) HomeDashboardSheet(state, { actions.refreshExit(true) }) { details = false }
}

@Composable
private fun HomeSummary(state: DashboardState, modifier: Modifier, onDetails: () -> Unit) {
    Row(
        modifier
            .clip(UiCorners.Card)
            .fluidClickable(depth = .98f, onClick = onDetails)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One column, one alignment: the profile, the node, where traffic leaves, then live
        // speed or the remaining quota. The mode is on the drum and the state on the badge.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.data.activeProfile?.name ?: tr(R.string.ui_choose_configuration),
                Modifier.testTag("home-profile-name"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            state.data.selected?.name?.let {
                Text(
                    it,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Keeps its height while the address loads, so the badge above does not shift.
            Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.CenterStart) {
                ExitLines(state)
            }
            SummaryLines(state)
        }
        AppGlyph(PcIcon.ChevronRight, Modifier.padding(start = 8.dp).size(16.dp), Muted)
    }
}

/** Live speed while connected, otherwise the remaining quota when the subscription reports it. */
@Composable
private fun SummaryLines(state: DashboardState) {
    val vpn = state.vpn
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
