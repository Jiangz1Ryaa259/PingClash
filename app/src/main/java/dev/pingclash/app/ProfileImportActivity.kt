// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a profile can be added; [section] groups the menu: links, text and files, QR codes. */
internal enum class ImportMethod(val title: Int, val glyph: PcIcon, val section: Int) {
    URL(R.string.import_from_url, PcIcon.Link, 0),
    CLIPBOARD(R.string.import_from_clipboard, PcIcon.Clipboard, 0),
    TEXT(R.string.import_from_text, PcIcon.Profile, 1),
    FILE(R.string.import_file, PcIcon.Folder, 1),
    BUNDLE(R.string.import_from_bundle, PcIcon.Archive, 1),
    IMAGE(R.string.import_from_image, PcIcon.Qr, 2),
    CAMERA(R.string.import_from_camera, PcIcon.Camera, 2),
}

/** Deliberately not parcelable/saveable: subscription credentials stay in memory. */
internal class ImportDraft(val content: String = "", val subscription: Boolean = true) {
    override fun toString() = "ImportDraft(redacted)"

    internal var form: ImportFormState? = null

    companion object {
        fun detect(text: String) = ImportDraft(text, subscriptionInput(text) != null)
    }
}

/** An isolated form has no sheet scrim and cannot dismiss itself on a blank tap. */
class ProfileImportActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val input: ImportAcquisitionModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val method =
            runCatching { ImportMethod.valueOf(intent.getStringExtra(METHOD).orEmpty()) }
                .getOrDefault(ImportMethod.URL)
        // Only a small enum is accepted from the launch Intent. No credential extras.
        intent.replaceExtras(null as Bundle?)
        setContent {
            val data by model.data.collectAsStateWithLifecycle()
            val ui by model.ui.collectAsStateWithLifecycle()
            PingClashTheme(data.client.effectiveTheme, data.client.dynamicColor) {
                ProfileImportContent(method, model, input, ui) { id ->
                    if (id != null) setResult(RESULT_OK, Intent().putExtra(RESULT_PROFILE, id))
                    finish()
                }
            }
        }
    }

    companion object {
        private const val METHOD = "import_method"
        internal const val RESULT_PROFILE = "imported_profile_id"

        internal fun intent(context: Context, method: ImportMethod) =
            Intent(context, ProfileImportActivity::class.java).putExtra(METHOD, method.name)
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // Explicit acquisition methods and result callbacks.
private fun ProfileImportContent(
    method: ImportMethod,
    model: AppViewModel,
    input: ImportAcquisitionModel,
    ui: AppUiState,
    onClose: (String?) -> Unit,
) {
    var agent by remember { mutableStateOf(input.draft.form?.agent ?: DEFAULT_SUBSCRIPTION_UA) }
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importFile("", uri)
        }
    val image =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) input.image(uri)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) input.camera(bitmap)
        }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        if (!input.opened) {
            input.opened = true
            when (method) {
                ImportMethod.CLIPBOARD -> input.clipboard()
                ImportMethod.FILE -> file.launch(arrayOf("*/*"))
                ImportMethod.BUNDLE ->
                    file.launch(arrayOf("application/zip", "application/octet-stream"))
                ImportMethod.IMAGE -> image.launch(arrayOf("image/*"))
                ImportMethod.CAMERA ->
                    runCatching { camera.launch(null) }.onFailure { input.unavailable() }
                ImportMethod.TEXT -> input.draft = ImportDraft(subscription = false)
                else -> Unit
            }
        }
    }
    LaunchedEffect(ui.importedProfileId) {
        if (ui.importedProfileId != null) onClose(ui.importedProfileId)
    }
    Box(Modifier.fillMaxSize().testTag("import-page")) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalIssueNotices provides model.notices
        ) {
            ImportDialog(
                ui.copy(
                    importIssue = input.error ?: ui.importIssue,
                    busy = ui.busy || input.loading,
                ),
                { onClose(null) },
                onUserAgent = { agent = it },
                onFile = { file.launch(arrayOf("*/*")) },
                initial = input.draft,
            ) { name, body, subscription ->
                input.error = null
                if (subscription) model.subscribe(name, body.trim(), agent)
                else model.importText(name, body)
            }
        }
        if (input.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
    }
    IssueNoticeHost(model.notices)
}

/** Decoding uses bounded IO; neither clipboard nor image text is logged or fetched. */
internal class ImportAcquisitionModel(application: android.app.Application) :
    AndroidViewModel(application) {
    var opened = false
    var draft by mutableStateOf(ImportDraft())
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    fun unavailable() {
        error = tr(R.string.import_camera_unavailable)
    }

    fun clipboard() {
        val clipboard =
            getApplication<android.app.Application>().getSystemService(ClipboardManager::class.java)
        val item =
            runCatching {
                    clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?.toString()
                }
                .getOrNull()
        if (item.isNullOrBlank()) {
            error = tr(R.string.import_clipboard_empty)
            return
        }
        accept(item)
    }

    private fun accept(text: String) {
        if (
            text.length > IMPORT_TEXT_LIMIT ||
                text.toByteArray(Charsets.UTF_8).size > IMPORT_TEXT_LIMIT
        ) {
            error = tr(R.string.ui_import_too_large)
            return
        }
        draft = ImportDraft.detect(text)
        error = null
    }

    fun image(uri: Uri) = decode {
        QrImport.decode(getApplication<android.app.Application>().contentResolver, uri)
    }

    fun camera(bitmap: Bitmap) = decode {
        try {
            QrImport.decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(read: () -> String?) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { read() }
                ensureActive()
                if (result == null) error = tr(R.string.import_qr_missing) else accept(result)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = tr(R.string.import_qr_missing)
            } finally {
                loading = false
            }
        }
    }
}

internal const val IMPORT_TEXT_LIMIT = MAX_FEED_BYTES
