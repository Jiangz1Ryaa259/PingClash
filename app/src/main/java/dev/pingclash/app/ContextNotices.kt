// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import dev.pingclash.diagnostics.Models

/** Contextual explanations use the same dismissible overlay as operation notices. */
@Composable
internal fun ContextReminder(identity: String, detail: String, active: Boolean) {
    val shared = LocalIssueNotices.current
    val center = shared ?: remember { IssueNoticeCenter() }
    DisposableEffect(identity, center) { onDispose { center.remove(identity) } }
    LaunchedEffect(identity, detail, active) {
        if (active)
            center.publish(
                IssueNotice(
                    identity,
                    tr(R.string.issue_reminder),
                    detail,
                    IssueStage.OPERATION,
                    IssueSeverity.REMINDER,
                    "context-reminder",
                )
            )
        else center.remove(identity)
    }
    if (shared == null) IssueNoticeHost(center)
}

internal fun diagnosticContextDetail(steps: List<Models.Step>): String =
    steps
        .filter { it.state == Models.State.STALE || it.state == Models.State.BLOCKED }
        .map { "${it.stage.title()} · ${it.state.title()}\n${it.code.message()}" }
        .distinct()
        .joinToString("\n\n")

@Composable
internal fun DiagnosticContextNotice(identity: String, steps: List<Models.Step>, running: Boolean) {
    val detail = if (running) "" else diagnosticContextDetail(steps)
    ContextReminder(
        "diagnostic-context:$identity:${detail.hashCode()}",
        detail,
        detail.isNotEmpty(),
    )
}

internal fun visibleDiagnosticSteps(steps: List<Models.Step>): List<Models.Step> =
    if (steps.any { it.state == Models.State.STALE }) emptyList()
    else steps.filterNot { it.state == Models.State.BLOCKED }

@Composable
internal fun DiagnosticConclusion(
    report: Models.Report,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    if (diagnosticContextDetail(report.steps).isEmpty()) Text(report.conclusion(), style = style)
}

@Composable
internal fun AuxiliaryEvidence(steps: List<Models.Step>) {
    val visible = visibleDiagnosticSteps(steps)
    if (visible.isNotEmpty()) {
        Text(tr(R.string.evidence_timeline), style = MaterialTheme.typography.titleMedium)
        visible.forEach { StepCard(it) }
    }
}
