// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Standalone dashboard previews/tests use the same notice as the app host. */
@Composable
internal fun VpnErrorBadge(vpn: VpnUiState, modifier: Modifier = Modifier) {
    if (LocalIssueNotices.current != null) return
    val center = remember { IssueNoticeCenter() }
    LaunchedEffect(vpn.failureId, vpn.failureCode, vpn.message) {
        center.publish(vpnIssue(vpn, "vpn:${vpn.failureId}:${vpn.failureCode}"))
    }
    androidx.compose.foundation.layout.Box(modifier) { IssueNoticeHost(center) }
}
