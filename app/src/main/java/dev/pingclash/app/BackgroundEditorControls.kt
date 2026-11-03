// SPDX-License-Identifier: GPL-3.0-only
// Swatch sizes and fill presets are the documented design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val FillPresets =
    listOf(0xFFFFFFFF, 0xFFF2F2F7, 0xFF1C1C1E, 0xFFF7D6DE, 0xFFDCEBFF).map { it.toInt() }

/** The hint, the Fit fill colours and the Fill/Fit switch under the editor's frame. */
@Composable
internal fun EditorControls(frame: BackgroundFrame, update: (BackgroundFrame) -> Unit) {
    Text(
        tr(if (frame.fit) R.string.ui_background_fit_hint else R.string.ui_background_fill_hint),
        Modifier.fillMaxWidth(),
        color = Color(0xFF8E8E93),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    if (frame.fit) FillSwatches(frame, update)
    Spacer(Modifier.height(12.dp))
    // Fit opens on the whole picture, so the fill shows at once; Fill starts centred and covered.
    ModeCapsule(frame.fit) {
        update(frame.copy(fit = it, zoom = if (it) 0f else 1f, x = 0f, y = 0f))
    }
    Spacer(Modifier.height(20.dp))
}

/**
 * Colours around a fitted picture: the picture's own edge colour, the presets, then the colours the
 * user added. "+" adds one in the colour editor; a long press edits or deletes an added colour.
 */
@Composable
private fun FillSwatches(frame: BackgroundFrame, update: (BackgroundFrame) -> Unit) {
    var edit by remember { mutableStateOf<ColorEdit?>(null) }
    val (builtIn, added) = fillColors(frame)
    // The picker starts from these. Pages over a picture keep their own theme and dim layer, so
    // it gives no dark-colour warning.
    val palette = (builtIn + added).map(::hexOfFill)
    fun open(original: Int?) =
        ColorEdit(original?.let(::hexOfFill), hexOfFill(original ?: frame.fill), palette, false)
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (builtIn + added).forEachIndexed { index, color ->
                FillSwatch(
                    color,
                    index,
                    color == frame.fill,
                    onPick = { update(frame.copy(fill = color)) },
                    onEdit = if (index < builtIn.size) null else ({ edit = open(color) }),
                )
            }
            AddFillButton { edit = open(null) }
        }
    }
    edit?.let { FillColorDialog(it, frame, update) { edit = null } }
}

/** The picture's edge colour and the presets, then the added colours not among them. */
private fun fillColors(frame: BackgroundFrame): Pair<List<Int>, List<Int>> {
    val builtIn = (listOf(frame.auto) + FillPresets).distinct()
    return builtIn to frame.custom.filterNot { it in builtIn }
}

/** Adds, changes or deletes one of the user's fill colours, and makes the result the fill. */
@Composable
private fun FillColorDialog(
    edit: ColorEdit,
    frame: BackgroundFrame,
    update: (BackgroundFrame) -> Unit,
    onClose: () -> Unit,
) {
    val (builtIn, added) = fillColors(frame)
    val original = edit.original?.let(::fillOf)
    ColorEditDialog(
        edit,
        onSave = { hex ->
            val color = fillOf(hex)
            val custom =
                if (original == null) added + color
                else added.map { if (it == original) color else it }
            update(
                frame.copy(
                    fill = color,
                    custom =
                        custom
                            .filterNot { it in builtIn }
                            .distinct()
                            .takeLast(BackgroundFrame.MAX_CUSTOM),
                )
            )
        },
        onDelete =
            original?.let { gone ->
                {
                    val fill = if (frame.fill == gone) frame.auto else frame.fill
                    update(frame.copy(fill = fill, custom = added - gone))
                }
            },
        onClose = onClose,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FillSwatch(
    color: Int,
    index: Int,
    chosen: Boolean,
    onPick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val editLabel = tr(R.string.palette_edit_color)
    Box(
        Modifier.size(30.dp)
            .then(
                if (chosen) Modifier.border(2.dp, Color.White, CircleShape).padding(4.dp)
                else Modifier
            )
            .clip(CircleShape)
            .background(Color(color))
            .border(0.5.dp, Color(0xFF636366), CircleShape)
            .combinedClickable(
                onLongClickLabel = onEdit?.let { editLabel },
                onLongClick = onEdit,
                onClick = onPick,
            )
            .testTag("background-fill-$index")
            .semantics {
                this.selected = chosen
                contentDescription =
                    if (index == 0) tr(R.string.ui_background_auto_color) else hexOfFill(color)
            }
    )
}

@Composable
private fun AddFillButton(onClick: () -> Unit) {
    val label = tr(R.string.palette_add)
    Box(
        Modifier.size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFF2C2C2E))
            .border(0.5.dp, Color(0xFF636366), CircleShape)
            .clickable(onClick = onClick)
            .testTag("background-fill-add")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AppGlyph(PcIcon.Add, Modifier.size(18.dp), Color.White)
    }
}

private fun hexOfFill(color: Int) = "#%06X".format(color and 0xFFFFFF)

private fun fillOf(hex: String) = solidColor(hex).toArgb()

@Composable
private fun ModeCapsule(fit: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 72.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)).padding(3.dp)) {
            listOf(false to R.string.ui_background_fill, true to R.string.ui_background_fit)
                .forEach { (mode, label) ->
                    val chosen = mode == fit
                    Text(
                        tr(label),
                        Modifier.width(96.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (chosen) Color(0xFF636366) else Color.Transparent)
                            .clickable { if (!chosen) onChange(mode) }
                            .padding(vertical = 7.dp)
                            .testTag(if (mode) "background-mode-fit" else "background-mode-fill")
                            .semantics { selected = chosen },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
        }
    }
}
