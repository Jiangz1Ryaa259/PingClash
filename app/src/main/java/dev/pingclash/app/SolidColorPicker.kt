// SPDX-License-Identifier: GPL-3.0-only
// Swatch geometry and channel ranges are visual design constants.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Solid background colour: one row of swatches ending in a white "+" button. Tap applies a colour;
 * "+" adds one and a long press edits or deletes one in a dialog. At least one swatch always
 * remains. [onApply] saves the colour and/or the palette in one update.
 */
@Composable
internal fun SolidColorPicker(
    value: String,
    swatches: List<String>,
    onApply: (color: String?, swatches: List<String>?) -> Unit,
) {
    var edit by remember { mutableStateOf<ColorEdit?>(null) }
    Column(
        Modifier.testTag("solid-color-picker"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(tr(R.string.solid_color), style = MaterialTheme.typography.labelLarge, color = Muted)
        SwatchRow(
            value.uppercase(),
            swatches,
            open = edit != null,
            // Tap applies a colour; a long press edits or deletes it.
            onSwatch = { hex, longPress ->
                if (longPress) edit = ColorEdit(hex, hex, swatches) else onApply(hex, null)
            },
            onToggle = { edit = if (edit == null) ColorEdit(null, value, swatches) else null },
        )
    }
    edit?.let { current ->
        val original = current.original
        ColorEditDialog(
            current,
            onSave = { hex ->
                onApply(
                    hex,
                    if (original == null) (swatches + hex).distinct().take(MAX_SOLID_SWATCHES)
                    else swatches.map { if (it == original) hex else it }.distinct(),
                )
            },
            // At least one swatch always remains.
            onDelete =
                original?.takeIf { swatches.size > 1 }?.let { { onApply(null, swatches - it) } },
            onClose = { edit = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchRow(
    selected: String,
    swatches: List<String>,
    open: Boolean,
    onSwatch: (hex: String, longPress: Boolean) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        swatches.forEach { hex ->
            val name = swatchName(hex)?.let { tr(it) } ?: hex
            val editLabel = tr(R.string.palette_edit_color)
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(solidColor(hex))
                    .border(
                        if (hex == selected) 2.dp else .7.dp,
                        if (hex == selected) Mint else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .combinedClickable(
                        onLongClickLabel = editLabel,
                        onLongClick = { onSwatch(hex, true) },
                        onClick = { onSwatch(hex, false) },
                    )
                    .testTag("solid-swatch-$hex")
                    .semantics { contentDescription = name }
            )
        }
        // "+" opens the editor and becomes "-"; "-" folds it away and cancels.
        val addLabel = tr(if (open) R.string.palette_close else R.string.palette_add)
        Box(
            Modifier.size(40.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onToggle)
                .testTag("solid-swatch-add")
                .semantics { contentDescription = addLabel },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(open, label = "palette-toggle") { minus ->
                AppGlyph(
                    if (minus) PcIcon.Minus else PcIcon.Add,
                    Modifier.size(22.dp),
                    Color(0xFF17212B),
                )
            }
        }
    }
}
