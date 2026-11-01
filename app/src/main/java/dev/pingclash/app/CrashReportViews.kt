// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * After a crash (or a contained core error) the next launch says so once, in the top-right banner.
 * Fixed copy only: the report itself is opened from Settings > About.
 */
@Composable
internal fun CrashReportNotice(center: IssueNoticeCenter) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val kind = CrashRecorder.takeUnseen(context) ?: return@LaunchedEffect
        val closed = kind == CrashKind.CLOSED
        center.publish(
            IssueNotice(
                "crash-report",
                tr(if (closed) R.string.crash_last_title else R.string.crash_contained_title),
                tr(if (closed) R.string.crash_last_detail else R.string.crash_contained_detail),
                IssueStage.OPERATION,
                if (closed) IssueSeverity.WARNING else IssueSeverity.REMINDER,
                "crash-report",
            )
        )
    }
}

/** Settings > About: the last crash report, to read, copy for a bug report, or delete. */
@Composable
internal fun CrashReportCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashRecorder.last(context)) }
    var open by remember { mutableStateOf(false) }
    val saved = report ?: return
    SettingsCard {
        Text(tr(R.string.crash_report), style = MaterialTheme.typography.titleMedium)
        StatLine(tr(R.string.crash_report_saved), localDateTime(saved.savedAt / 1000))
        Row {
            OutlinedButton({ open = true }, Modifier.testTag("crash-report-view")) {
                Text(tr(R.string.crash_report_view))
            }
            TextButton(
                {
                    CrashRecorder.clear(context)
                    report = null
                },
                Modifier.testTag("crash-report-delete"),
            ) {
                Text(tr(R.string.delete))
            }
        }
    }
    if (open) CrashReportDialog(saved.text) { open = false }
}

@Composable
private fun CrashReportDialog(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr(R.string.crash_report)) },
        text = {
            SelectionContainer {
                Text(
                    text,
                    Modifier.heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("crash-report-text"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        },
        dismissButton = {
            TextButton(
                {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("PingClash crash report", text))
                    copied = true
                },
                Modifier.testTag("crash-report-copy"),
            ) {
                Text(tr(if (copied) R.string.ui_copied else R.string.crash_report_copy))
            }
        },
        confirmButton = { TextButton(onClose) { Text(tr(R.string.close)) } },
    )
}
