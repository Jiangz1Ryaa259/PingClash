// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.delay

private const val MAX_TERMINAL_OUTPUT = 65536
private const val COPY_FEEDBACK_MS = 1300L

internal fun terminalOutput(session: TerminalSession?): String =
    session?.emulator?.screen?.transcriptText.orEmpty().takeLast(MAX_TERMINAL_OUTPUT)

internal fun copyTerminalOutput(context: Context, text: String) {
    val clip =
        ClipData.newPlainText(tr(R.string.terminal_output), text.takeLast(MAX_TERMINAL_OUTPUT))
    // Supported by clipboard overlays on newer Android; not an access-control
    // guarantee. Only an explicit copy action reaches this function.
    clip.description.extras =
        PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

internal fun terminalShareIntent(text: String) =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text.takeLast(MAX_TERMINAL_OUTPUT))
    }

@Composable
internal fun TerminalOutputActions(
    session: TerminalSession?,
    onShareFailed: () -> Unit = {},
    keyboard: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MS)
            copied = false
        }
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton(
            keyboard,
            enabled = session != null,
            modifier = Modifier.testTag("terminal-keyboard"),
        ) {
            Text(tr(R.string.terminal_keyboard))
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            {
                copyTerminalOutput(context, terminalOutput(session))
                copied = true
            },
            enabled = session != null,
            modifier = Modifier.testTag("terminal-copy"),
        ) {
            Text(tr(if (copied) R.string.ui_copied else R.string.terminal_copy_output))
        }
        TextButton(
            { preview = terminalOutput(session) },
            enabled = session != null,
            modifier = Modifier.testTag("terminal-share"),
        ) {
            Text(tr(R.string.terminal_share_output))
        }
    }
    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            modifier = Modifier.testTag("terminal-share-preview"),
            title = { Text(tr(R.string.terminal_output)) },
            text = {
                Column {
                    Text(
                        tr(R.string.terminal_share_review),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SelectionContainer {
                        Text(
                            text,
                            Modifier.heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag("terminal-share-text"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        terminalShareIntent(text),
                                        tr(R.string.terminal_share_output),
                                    )
                                )
                            }
                            .onSuccess { preview = null }
                            .onFailure {
                                preview = null
                                onShareFailed()
                            }
                    },
                    Modifier.testTag("terminal-share-confirm"),
                ) {
                    Text(tr(R.string.terminal_share_output))
                }
            },
            dismissButton = {
                TextButton({ preview = null }, Modifier.testTag("terminal-share-cancel")) {
                    Text(tr(R.string.cancel))
                }
            },
        )
    }
}
