// SPDX-License-Identifier: GPL-3.0-only
// Surface opacity and responsive layout metrics are deliberate visual design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class DashboardState(
    val data: AppData,
    val vpn: VpnUiState,
    val diagnosis: DiagnosticUiState,
    val tests: ProxyTestState,
    val ready: Boolean,
    val busy: Boolean,
    val services: ServiceCheckState = ServiceCheckState(),
    val exit: ExitState = ExitState(),
)

internal data class DashboardActions(
    val connect: () -> Unit,
    val nodes: () -> Unit,
    val diagnose: () -> Unit,
    val test: () -> Unit,
    val mode: (String) -> Unit = {},
    val selectProfile: (String) -> Unit = {},
    val themeMode: (String) -> Unit = {},
    val serviceTest: (List<String>) -> Unit = {},
    /** Checks the main proxy's exit; true checks even when a fresh result exists. */
    val refreshExit: (Boolean) -> Unit = {},
)

private val HOME_CARD_WIDTH = 640.dp

/** Below this landscape width the cards stack beside the badge instead of three columns. */
private val THREE_COLUMNS = 900.dp

@Composable
internal fun DashboardScreen(state: DashboardState, actions: DashboardActions) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(bottom = LocalNavigationClearance.current)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("dashboard")
    ) {
        val shown = if (maxHeight < 560.dp || LocalDensity.current.fontScale >= 1.4f) 2 else 4
        if (maxWidth > maxHeight && maxWidth < THREE_COLUMNS)
        // A phone held sideways: the badge beside the two cards, one above the other.
        Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeConnection(state, actions, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeConfiguration(state, actions)
                    HomeServiceChecks(state, actions.serviceTest, 2)
                }
            }
        else if (maxWidth > maxHeight)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeConnection(state, actions, Modifier.weight(1.2f).fillMaxHeight())
                Box(Modifier.weight(1f)) { HomeConfiguration(state, actions) }
                Box(Modifier.weight(1.3f)) { HomeServiceChecks(state, actions.serviceTest, shown) }
            }
        else
            Column(
                Modifier.fillMaxHeight().readableWidth(HOME_CARD_WIDTH),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeConnection(state, actions, Modifier.weight(1f).fillMaxWidth())
                // Portrait: one card, the website checks docked under the summary.
                HomeConfiguration(state, actions) {
                    HomeServiceStrip(state, actions.serviceTest, shown)
                }
            }
    }
}

@Composable
private fun HomeConnection(state: DashboardState, actions: DashboardActions, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.testTag("home-control")) {
        val identity =
            CardIdentity(
                state.data.activeProfile?.name.orEmpty(),
                modeLabel(state.data.client.mode),
            )
        androidx.compose.runtime.CompositionLocalProvider(LocalCardIdentity provides identity) {
            LanyardPowerControl(
                state.vpn.phase,
                connectionControlEnabled(state),
                connectLabel(state),
                maxHeight.coerceAtLeast(112.dp),
                actions.connect,
            )
        }
        if (state.vpn.stage == VpnPhase.ERROR)
            VpnErrorBadge(state.vpn, Modifier.align(Alignment.TopEnd).padding(top = 6.dp))
        // Beside a sidebar the app is already named there and on the badge.
        if (!LocalNavigationRail.current)
            PageHeading(
                "PingClash",
                Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 6.dp),
                "page-title-home",
            )
    }
}

internal fun websiteMeasurementEnabled(state: DashboardState): Boolean =
    !state.busy && state.vpn.phase !in setOf("connecting", "stopping")

internal fun measurementEnabled(state: DashboardState): Boolean {
    val usable = state.ready && state.data.selected != null
    val blocked =
        listOf(
                state.busy,
                state.diagnosis.running,
                state.tests.running,
                state.vpn.phase in setOf("connecting", "stopping"),
            )
            .any { it }
    return usable && !blocked
}

internal fun connectionControlEnabled(state: DashboardState): Boolean {
    return state.vpn.active || (state.ready && !state.data.loadFailed && !state.busy)
}

@Composable
internal fun ConnectionBadge(vpn: VpnUiState) {
    val color =
        when (vpn.phase) {
            "established" -> Success
            "error" -> MaterialTheme.colorScheme.error
            else -> Muted
        }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = UiCorners.Pill) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.size(6.dp).background(color, CircleShape))
            Text(
                tr(
                    when (vpn.phase) {
                        "established" -> R.string.vpn_tunnel_established
                        "connecting" -> R.string.connecting
                        "stopping" -> R.string.disconnecting
                        "error" -> R.string.ui_connection_failed
                        else -> R.string.not_connected
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun UrlMeasurement(delay: ProxyDelay?, modifier: Modifier) {
    Column(modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (delay?.state in setOf("members", "members_running")) {
            Text(
                groupDelayText(requireNotNull(delay)),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        } else if (delay?.state == "passed") {
            val tone = latencyColor(delay.elapsedMs)
            Text(
                tr(R.string.latency_url, "${delay.elapsedMs.coerceAtLeast(1)} ms"),
                style = MaterialTheme.typography.titleLarge,
                color = tone,
            )
            Text(
                tr(R.string.ui_last_url_result, tr(latencyLabel(delay.elapsedMs))),
                color = tone,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                tr(
                    when (delay?.state) {
                        "running" -> R.string.ui_checking
                        "timeout" -> R.string.timeout
                        "failed" -> R.string.failed
                        else -> R.string.ui_not_measured
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (delay?.state in setOf("failed", "timeout")) MaterialTheme.colorScheme.error
                    else Muted,
            )
        }
    }
}

@Composable
internal fun connectLabel(state: DashboardState): String =
    tr(
        when {
            state.vpn.stage == VpnPhase.CONNECTING -> R.string.ui_cancel_connection
            state.vpn.stage == VpnPhase.STOPPING -> R.string.disconnecting
            state.vpn.active -> R.string.disconnect
            state.data.selected == null -> R.string.ui_choose_configuration
            else -> R.string.connect_vpn
        }
    )

@Composable
internal fun latencyColor(elapsed: Long) =
    when (latencyLabel(elapsed)) {
        R.string.ui_latency_fast -> Success
        R.string.ui_latency_moderate -> Warning
        else -> MaterialTheme.colorScheme.error
    }
