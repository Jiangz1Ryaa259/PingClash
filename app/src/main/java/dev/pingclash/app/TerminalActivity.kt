// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File

internal class TerminalModel(application: Application) : AndroidViewModel(application) {
    init {
        TerminalPalette.install()
    }

    val sessions = mutableStateListOf<TerminalSession>()
    var selected by mutableStateOf(0)
    var revision by mutableIntStateOf(0)
    private var viewRef = java.lang.ref.WeakReference<TerminalView>(null)
    var view: TerminalView?
        get() = viewRef.get()
        set(value) {
            viewRef = java.lang.ref.WeakReference(value)
        }

    var startFailed by mutableStateOf(false)
    internal val notices = IssueNoticeCenter()
    var paste by mutableStateOf<String?>(null)
    private val app = application
    var noticeAccepted by
        mutableStateOf(
            application
                .getSharedPreferences("terminal_ui", 0)
                .getBoolean("uid_notice_accepted", false)
        )

    fun acceptNotice() {
        app.getSharedPreferences("terminal_ui", 0)
            .edit()
            .putBoolean("uid_notice_accepted", true)
            .apply()
        noticeAccepted = true
    }

    private val callbacks =
        object : TerminalCallbacks() {
            override fun onTextChanged(s: TerminalSession) {
                if (sessions.getOrNull(selected) === s) view?.onScreenUpdated()
            }

            override fun sessionFinishedMessage(status: Int) =
                tr(R.string.terminal_process_exit, status)

            override fun sessionStartFailedMessage() = "\r\n" + tr(R.string.terminal_start_failed)

            override fun onSessionStartFailed(s: TerminalSession) {
                startFailed = true
                revision++
            }

            override fun onSessionFinished(s: TerminalSession) {
                revision++
                onTextChanged(s)
            }

            override fun onColorsChanged(s: TerminalSession) {
                onTextChanged(s)
            }

            override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
                copyTerminalOutput(app, text)
            }

            override fun onPasteTextFromClipboard(s: TerminalSession) {
                requestPaste()
            }
        }

    fun requestPaste() {
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
        paste = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.take(16384)
    }

    fun create() {
        if (!noticeAccepted || sessions.size >= 3) return
        val cwd = File(app.cacheDir, "terminal").apply { mkdirs() }
        val env =
            arrayOf(
                "HOME=${cwd.absolutePath}",
                "TMPDIR=${cwd.absolutePath}",
                "PATH=/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "ENV=/dev/null",
                "HISTFILE=/dev/null",
                "PS1=${TerminalPalette.prompt}",
            )
        startFailed = false
        sessions.add(
            TerminalSession(
                "/system/bin/sh",
                cwd.absolutePath,
                arrayOf("sh", "-i"),
                env,
                2000,
                callbacks,
            )
        )
        selected = sessions.lastIndex
    }

    fun closeCurrent() {
        sessions.getOrNull(selected)?.finishIfRunning()
        if (sessions.isNotEmpty()) sessions.removeAt(selected)
        selected = selected.coerceAtMost((sessions.size - 1).coerceAtLeast(0))
    }

    fun stopAll() {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        selected = 0
        paste = null
        view = null
    }

    fun send(text: String) {
        sessions.getOrNull(selected)?.write(text)
    }

    fun attach(v: TerminalView) {
        view = v
        v.setTerminalViewClient(
            object : TerminalCallbacks() {
                override fun onSingleTapUp(e: android.view.MotionEvent) {
                    (v as? TerminalInputView)?.showKeyboard()
                }
            }
        )
        // applyDimension follows Android 14's non-linear font scaling; scaledDensity does not.
        val size =
            android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                TERMINAL_TEXT_SP,
                app.resources.displayMetrics,
            )
        v.setTextSize(size.toInt().coerceIn(12, 40))
    }

    override fun onCleared() {
        stopAll()
    }
}

class TerminalActivity : ComponentActivity() {
    internal lateinit var terminal: TerminalModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        terminal = ViewModelProvider(this)[TerminalModel::class.java]
        setContent {
            PingClashTheme("dark", dynamicColor = false) { TerminalPage(terminal) { finish() } }
        }
    }

    override fun onResume() {
        super.onResume()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.postInvalidateOnAnimation()
    }

    // The retained ViewModel owns the PTY. Backgrounding or a configuration
    // change must not terminate an explicitly started command.
    override fun onStop() {
        super.onStop()
        terminal.paste = null
    }
}

@Composable
private fun TerminalPage(model: TerminalModel, back: () -> Unit) {
    val revision = model.revision
    LaunchedEffect(model.noticeAccepted) {
        if (model.noticeAccepted && model.sessions.isEmpty()) model.create()
    }
    Column(
        Modifier.fillMaxSize()
            .background(Color(TerminalPalette.background))
            .safeDrawingPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(back) { Text(tr(R.string.back)) }
            TextButton(
                model::create,
                enabled = model.noticeAccepted && model.sessions.size < 3,
                modifier = Modifier.testTag("terminal-new"),
            ) {
                Text(tr(R.string.new_terminal))
            }
            TextButton(model::closeCurrent, enabled = model.sessions.isNotEmpty()) {
                Text(tr(R.string.close_session))
            }
        }
        LaunchedEffect(model.startFailed) {
            if (model.startFailed)
                model.notices.publish(
                    IssueNotice(
                        java.util.UUID.randomUUID().toString(),
                        tr(R.string.issue_warning),
                        tr(R.string.terminal_start_failed),
                        IssueStage.OPERATION,
                        IssueSeverity.WARNING,
                        "terminal-warning",
                    )
                )
        }

        if (model.sessions.isEmpty()) {
            Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    tr(R.string.mobile_terminal),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(16.dp))

                Spacer(Modifier.height(12.dp))
            }
        } else {
            LazyRow {
                items(model.sessions.size) { i ->
                    FilterChip(
                        model.selected == i,
                        { model.selected = i },
                        label = {
                            Text(
                                "sh ${i+1}" +
                                    (if (model.sessions[i].isRunning) "" else tr(R.string.exited))
                            )
                        },
                    )
                }
            }
            val session = model.sessions.getOrNull(model.selected)
            TerminalOutputActions(
                session,
                onShareFailed = {
                    model.notices.publish(
                        IssueNotice(
                            java.util.UUID.randomUUID().toString(),
                            tr(R.string.issue_warning),
                            tr(R.string.terminal_share_failed),
                            IssueStage.OPERATION,
                            IssueSeverity.WARNING,
                            "terminal-warning",
                        )
                    )
                },
            ) {
                (model.view as? TerminalInputView)?.showKeyboard()
            }
            key(session) {
                AndroidView(
                    factory = { context ->
                        TerminalInputView(
                                context.createConfigurationContext(
                                    UiStrings.resources().configuration
                                )
                            )
                            .apply {
                                model.attach(this)
                                session?.let { attachSession(it) }
                                requestFocus()
                            }
                    },
                    update = { v ->
                        model.view = v
                        if (revision >= 0) v.onScreenUpdated()
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal-view"),
                )
            }
            LazyRow(Modifier.fillMaxWidth()) {
                items(8) { i ->
                    val key =
                        listOf("Ctrl+C", "Tab", "Esc", "↑", "↓", "←", "→", tr(R.string.paste))[i]
                    TextButton(
                        {
                            if (i == 7) model.requestPaste()
                            else
                                model.send(
                                    listOf(
                                        "\u0003",
                                        "\t",
                                        "\u001b",
                                        "\u001b[A",
                                        "\u001b[B",
                                        "\u001b[D",
                                        "\u001b[C",
                                    )[i]
                                )
                        },
                        Modifier.testTag("terminal-key-$i"),
                    ) {
                        Text(key)
                    }
                }
            }
        }
    }
    if (!model.noticeAccepted)
        AlertDialog(
            onDismissRequest = back,
            title = { Text(tr(R.string.terminal_warning_title)) },
            text = { Text(tr(R.string.terminal_warning_body)) },
            confirmButton = {
                TextButton(model::acceptNotice, Modifier.testTag("terminal-notice-accept")) {
                    Text(tr(R.string.terminal_warning_accept))
                }
            },
            dismissButton = { TextButton(back) { Text(tr(R.string.cancel)) } },
        )
    model.paste?.let { text ->
        AlertDialog(
            onDismissRequest = { model.paste = null },
            title = { Text(tr(R.string.paste_into_terminal)) },
            text = {
                Text(
                    trPlural(
                        R.plurals.count_text_containing_newlines_may_execute,
                        (text.length).toInt(),
                        text.length,
                    )
                )
            },
            confirmButton = {
                TextButton({
                    model.sessions.getOrNull(model.selected)?.emulator?.paste(text)
                    model.paste = null
                }) {
                    Text(tr(R.string.paste))
                }
            },
            dismissButton = { TextButton({ model.paste = null }) { Text(tr(R.string.cancel_2)) } },
        )
    }
    IssueNoticeHost(model.notices)
}

private const val TERMINAL_TEXT_SP = 14f
