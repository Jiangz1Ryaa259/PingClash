// SPDX-License-Identifier: GPL-3.0-only
// Editor metrics, dim-layer strengths and fill presets are the documented design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The stored picture and its framing, reloaded whenever either changes. */
@Composable
internal fun rememberFramedPicture(): Pair<ImageBitmap, BackgroundFrame>? {
    val context = LocalContext.current
    val revision = CustomBackground.revision.intValue
    val picture by
        produceState<Pair<ImageBitmap, BackgroundFrame>?>(null, revision) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            CustomBackground.load(context)?.asImageBitmap()?.let {
                                it to BackgroundFrames.load(context)
                            }
                        }
                        .getOrNull()
                }
        }
    return picture
}

/** What the editor frames: a newly [staged] picture, or the stored one. */
@Composable
private fun rememberEditorPicture(staged: BackgroundFrame?): Pair<ImageBitmap, BackgroundFrame>? {
    val stored = rememberFramedPicture()
    val context = LocalContext.current
    val pending by
        produceState<Pair<ImageBitmap, BackgroundFrame>?>(null, staged) {
            value =
                staged?.let { frame ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                                CustomBackground.load(context, pending = true)?.asImageBitmap()
                            }
                            .getOrNull()
                            ?.let { it to frame }
                    }
                }
        }
    return if (staged == null) stored else pending
}

/** The user's picture behind every page, under a theme-aware dim layer so text stays legible. */
@Composable
internal fun CustomImageBackdrop(modifier: Modifier = Modifier) {
    val picture = rememberFramedPicture()
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val scrim = if (light) Color.White.copy(alpha = .32f) else Color.Black.copy(alpha = .4f)
    Canvas(modifier.testTag("custom-background")) {
        picture?.let { (image, frame) -> drawFramedBackground(image, frame) }
        drawRect(scrim)
    }
}

/** Opens the system photo picker: no storage permission, access to the chosen image only. */
@Composable
internal fun rememberBackgroundPicker(model: AppViewModel): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) model.importBackground(uri)
        }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/**
 * Full-screen framing editor. The frame has the screen's proportions, so what it shows is what the
 * background shows. Fill keeps the screen covered while the picture is zoomed and moved; Fit lets
 * it shrink, and the rest of the screen takes the chosen colour.
 */
@Composable
internal fun BackgroundEditor(
    staged: BackgroundFrame?,
    onDone: (BackgroundFrame) -> Unit,
    onCancel: () -> Unit,
) {
    val picture = rememberEditorPicture(staged)
    var edited by remember { mutableStateOf<BackgroundFrame?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val image = picture?.first
    val frame = edited ?: picture?.second ?: BackgroundFrame()
    fun update(next: BackgroundFrame) {
        edited =
            image?.let {
                next.clamped(
                    IntSize(it.width, it.height),
                    box.width.toFloat(),
                    box.height.toFloat(),
                )
            } ?: next
    }
    Dialog(
        onDismissRequest = onCancel,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .testTag("background-editor")
        ) {
            EditorTopBar(onCancel) { onDone(frame) }
            EditorFrame(image, frame, Modifier.weight(1f), { box = it }) { pan, zoom ->
                // Each touch event moves on from the latest edit, which may be newer than the
                // frame this composition saw: several events can arrive between two frames.
                val now = edited ?: picture?.second ?: BackgroundFrame()
                if (box.width > 0 && box.height > 0)
                    update(
                        now.copy(
                            zoom = now.zoom * zoom,
                            x = now.x + pan.x / box.width,
                            y = now.y + pan.y / box.height,
                        )
                    )
            }
            EditorControls(frame, ::update)
        }
    }
}

@Composable
private fun EditorTopBar(onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onCancel, Modifier.testTag("background-cancel")) {
            Text(tr(R.string.cancel), color = Color.White)
        }
        Text(
            tr(R.string.ui_background_edit),
            Modifier.weight(1f),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        TextButton(onDone, Modifier.testTag("background-done")) {
            Text(tr(R.string.ui_done), color = Color(0xFF409CFF), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The picture inside a screen-shaped frame with thirds guides; pinch and drag reframe it. */
@Composable
private fun EditorFrame(
    image: ImageBitmap?,
    frame: BackgroundFrame,
    modifier: Modifier,
    onBox: (IntSize) -> Unit,
    onTransform: (Offset, Float) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val aspect =
        configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1)
    val shape = RoundedCornerShape(20.dp)
    // The gesture loop outlives recompositions; it must always call the newest handler.
    val transform by rememberUpdatedState(onTransform)
    Box(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(shape)
                .border(1.5.dp, Color.White.copy(alpha = .9f), shape)
                .onSizeChanged(onBox)
                .pointerInput(image) {
                    detectTransformGestures { _, pan, zoom, _ -> transform(pan, zoom) }
                }
                .testTag("background-frame")
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (image != null) drawFramedBackground(image, frame)
                // Rule-of-thirds guides help place the subject.
                val guide = Color.White.copy(alpha = .35f)
                for (i in 1..2) {
                    val x = size.width * i / 3
                    val y = size.height * i / 3
                    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    drawLine(guide, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
        }
    }
}

internal fun backgroundRejectionMessage(reason: BackgroundRejection) =
    tr(
        when (reason) {
            BackgroundRejection.NOT_AN_IMAGE -> R.string.ui_background_not_image
            BackgroundRejection.TOO_LARGE -> R.string.ui_background_too_large
            BackgroundRejection.UNREADABLE -> R.string.ui_background_unreadable
        }
    )
