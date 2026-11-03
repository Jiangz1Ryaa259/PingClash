// SPDX-License-Identifier: GPL-3.0-only
// Row spacing and tile shape follow the home details sheet.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val EXIT_REFRESH_MS = 10 * 60 * 1000L
private const val EXIT_SETTLE_MS = 3000L

/** The result for the route traffic takes now; none for an older route or while switching. */
internal fun DashboardState.currentExit(): ExitInfo? =
    exit.info?.takeIf { it.route == exitRoute(vpn, data) }

/**
 * Checks again when the VPN connects or disconnects, when the mode, profile or main proxy changes,
 * when the app comes back to the foreground (at most once a minute) and every ten minutes while it
 * stays open.
 */
@Composable
internal fun ExitRefreshEffect(state: DashboardState, refresh: (Boolean) -> Unit) {
    val route = exitRoute(state.vpn, state.data)
    val foreground = rememberForeground()
    val latest by rememberUpdatedState(refresh)
    LaunchedEffect(route, foreground) {
        if (route == null || !foreground) return@LaunchedEffect
        // Let a new session or a node change settle first; quick changes then check once.
        delay(EXIT_SETTLE_MS)
        latest(false)
        while (true) {
            delay(EXIT_REFRESH_MS)
            latest(true)
        }
    }
}

/** Two short lines for the bottom-right corner of the home card: country or region, network. */
@Composable
internal fun ExitLines(state: DashboardState, modifier: Modifier = Modifier) {
    val info = state.currentExit() ?: return
    val language = LocalUiLanguage.current
    Column(
        modifier.widthIn(max = 150.dp).testTag("home-exit"),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        listOf(
                exitCountryLabel(info.country, language),
                info.org.ifBlank { if (info.asn > 0) "AS${info.asn}" else "" },
            )
            .filter { it.isNotBlank() }
            .forEach { line ->
                Text(
                    line,
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
    }
}

/** The details sheet's IP section: everything known about the current address, and a refresh. */
@Composable
internal fun ExitSection(state: DashboardState, onRefresh: () -> Unit) {
    val info = state.currentExit()
    val language = LocalUiLanguage.current
    Column(
        Modifier.testTag("home-exit-details"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.ui_exit_title),
                Modifier.weight(1f),
                color = Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onRefresh,
                enabled = !state.exit.checking && exitRoute(state.vpn, state.data) != null,
                modifier = Modifier.testTag("home-exit-refresh"),
            ) {
                Text(
                    tr(
                        if (state.exit.checking) R.string.ui_exit_checking
                        else R.string.ui_exit_refresh
                    )
                )
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            if (info == null)
                Text(
                    if (state.exit.failed && !state.exit.checking) tr(R.string.ui_exit_failed)
                    else "—",
                    Modifier.padding(vertical = 10.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            else ExitRows(info, language)
        }
    }
}

@Composable
private fun ExitRows(info: ExitInfo, language: String) {
    val rows =
        listOf(
            R.string.ui_exit_country to exitCountryLabel(info.country, language),
            R.string.ui_exit_ip to info.ip,
            R.string.ui_exit_org to info.org,
            R.string.ui_exit_asn to if (info.asn > 0) "AS${info.asn}" else "",
            // Only while connected: which outbound the rules pick for this traffic.
            R.string.ui_exit_route to if (info.policy.isEmpty()) "" else exitRouteLabel(info),
            R.string.ui_exit_checked to localDateTime(info.checkedAt / 1000),
        )
    rows
        .filter { it.second.isNotBlank() }
        .forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr(label), color = Muted, style = MaterialTheme.typography.bodyMedium)
                Text(
                    value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
}
