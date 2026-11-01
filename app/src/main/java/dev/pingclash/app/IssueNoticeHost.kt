// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Floating notice geometry, not protocol/timeout policy.

package dev.pingclash.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REMINDER_DURATION = 5000L

@Composable
internal fun IssueNoticeHost(center: IssueNoticeCenter) {
    // Keep the passive card in the page's overlay. Its bounds and lifecycle
    // stay independent of native popup-window positioning and focus.
    Box(Modifier.fillMaxSize()) {
        val expanded = center.detail.value
        val window = LocalActivity.current?.window
        val revision = SecurePage.revision
        val protected = remember(window, revision) { SecurePage.isProtected(window) }
        val context = LocalContext.current
        var copied by remember(expanded?.id) { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(1300)
                copied = false
            }
        }
        val visible = if (expanded == null) center.current() else null
        val remaining = rememberNoticeFuse(visible, center)
        NoticeBanner(
            visible,
            center,
            { remaining.value },
            Modifier.align(AbsoluteAlignment.TopRight).statusBarsPadding().padding(12.dp),
        )
        expanded?.let { notice ->
            AlertDialog(
                onDismissRequest = { center.detail.value = null },
                title = { Text(notice.title) },
                properties =
                    DialogProperties(
                        securePolicy =
                            if (protected) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff
                    ),
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            issueStageTitle(notice.stage),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            notice.detail,
                            Modifier.testTag(notice.tag.removeSuffix("-badge") + "-detail"),
                        )
                        if (notice.tag !in setOf("context-reminder", "crash-report"))
                            Text(issueAdvice(notice), style = MaterialTheme.typography.bodySmall)
                    }
                },
                dismissButton = {
                    TextButton(
                        { copied = copyIssueSummary(context, notice) },
                        Modifier.testTag("issue-copy"),
                    ) {
                        Text(tr(if (copied) R.string.ui_copied else R.string.issue_copy))
                    }
                },
                confirmButton = {
                    TextButton({ center.detail.value = null }) { Text(tr(R.string.close)) }
                },
            )
        }
    }
}

/** Functional countdown: independent of decorative-motion and animator-scale settings. */
@Composable
private fun rememberNoticeFuse(notice: IssueNotice?, center: IssueNoticeCenter): State<Float> {
    val remaining = remember(notice?.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(notice?.id, center) {
        if (notice?.severity == IssueSeverity.REMINDER)
            coroutineScope {
                val ticker = launch {
                    val start = withFrameNanos { it }
                    while (isActive) {
                        val elapsed = withFrameNanos { it - start } / 1_000_000f
                        remaining.floatValue = (1f - elapsed / REMINDER_DURATION).coerceIn(0f, 1f)
                    }
                }
                delay(REMINDER_DURATION)
                ticker.cancel()
                remaining.floatValue = 0f
                center.dismiss(notice.id)
            }
    }
    return remaining
}
