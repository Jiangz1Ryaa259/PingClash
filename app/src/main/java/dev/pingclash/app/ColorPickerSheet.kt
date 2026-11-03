// SPDX-License-Identifier: GPL-3.0-only
// Sheet spacing, preview and swatch sizes are the picker's design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The colour picker, as a sheet from the bottom: Grid, Spectrum and Sliders choose the colour, the
 * edit's palette offers the colours already saved, and HEX takes a typed value. Nothing applies
 * until Add or Save. [onDelete] offers a delete action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorEditDialog(
    edit: ColorEdit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    // The grid, spectrum and sliders consume their drags, so picking never pulls the sheet down.
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        modifier = Modifier.testTag("solid-color-dialog"),
    ) {
        ColorEditor(edit, onSave, onDelete, onClose)
    }
}

private val pickerTabs = listOf("grid", "spectrum", "sliders")

/** Grid, Spectrum and Sliders: three ways to choose [live], one shown at a time. */
@Composable
private fun PickerTabs(live: Color, onLive: (Color) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("grid") }
    val names =
        listOf(R.string.ui_color_grid, R.string.ui_color_spectrum, R.string.ui_color_sliders).map {
            tr(it)
        }
    SegmentedControl(pickerTabs.zip(names), tab, true, { "color-tab-$it" }) { tab = it }
    // One height for every tab, so the sheet and its buttons stay put when switching.
    Box(Modifier.fillMaxWidth().aspectRatio(PICKER_ASPECT), contentAlignment = Alignment.Center) {
        when (tab) {
            "grid" -> ColorGrid(live, onLive)
            "spectrum" -> ColorSpectrum(live, onLive)
            else -> RgbSliders(live, onLive)
        }
    }
}

@Composable
private fun ColorEditor(
    edit: ColorEdit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var live by remember(edit) { mutableStateOf(solidColor(edit.start)) }
    var confirmContrast by remember(edit) { mutableStateOf(false) }
    fun applyColor() {
        onSave(hexOf(live))
        onClose()
    }
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .testTag("solid-color-editor"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetHeader(
            tr(if (edit.original == null) R.string.palette_add else R.string.palette_edit_color),
            tr(if (edit.original == null) R.string.palette_add_action else R.string.palette_save),
            onClose,
        ) {
            if (edit.warnDark && live.luminance() < .45f) confirmContrast = true else applyColor()
        }
        PickerTabs(live) { live = it }
        PreviewAndPalette(live, edit.palette) { live = solidColor(it) }
        HexField(hexOf(live)) { live = solidColor(it) }
        if (onDelete != null)
            SettingsSection {
                ActionRow(
                    tr(R.string.palette_delete_action),
                    "solid-color-delete",
                    color = MaterialTheme.colorScheme.error,
                ) {
                    onDelete()
                    onClose()
                }
            }
    }
    if (confirmContrast)
        ContrastWarning(
            onSave = {
                confirmContrast = false
                applyColor()
            },
            onDismiss = { confirmContrast = false },
        )
}

/** Cancel on the left, the title in the middle, the bold confirm action on the right. */
@Composable
private fun SheetHeader(title: String, action: String, onCancel: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onCancel) { Text(tr(R.string.cancel)) }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        TextButton(onAction, Modifier.testTag("solid-color-save")) {
            Text(action, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A large swatch of the colour being made, beside the saved colours to start from. */
@Composable
private fun PreviewAndPalette(live: Color, palette: List<String>, onPick: (String) -> Unit) {
    val current = hexOf(live)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(live)
                .border(.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .testTag("color-preview")
                .semantics { contentDescription = current }
        )
        if (palette.isNotEmpty())
            Column(
                Modifier.weight(1f).padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    tr(R.string.ui_color_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    palette.distinct().forEach { hex ->
                        val chosen = hex.equals(current, ignoreCase = true)
                        Box(
                            Modifier.size(30.dp)
                                .then(
                                    if (chosen)
                                        Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape,
                                            )
                                            .padding(4.dp)
                                    else Modifier
                                )
                                .clip(CircleShape)
                                .background(solidColor(hex))
                                .border(
                                    .5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                                .fluidClickable(depth = .9f) { onPick(hex) }
                                .semantics { contentDescription = hex }
                        )
                    }
                }
            }
    }
}

/** HEX in a grey capsule, as iOS shows it under the sliders. */
@Composable
private fun HexField(current: String, onValid: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(current, focused) {
        if (!focused) text = TextFieldValue(current, TextRange(current.length))
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr(R.string.solid_color_hex),
            Modifier.weight(1f),
            color = Muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        BasicTextField(
            text,
            { input ->
                val next = hexInput(input.text)
                // Normalising restores the "#" and drops other characters: keep the caret after
                // the digits typed so far, so a cleared field fills in typing order.
                val caret = hexInput(input.text.take(input.selection.end)).length
                text = TextFieldValue(next, TextRange(caret.coerceIn(1, next.length)))
                if (validHexColor(next)) onValid(next)
            },
            Modifier.size(width = 120.dp, height = 28.dp).testTag("solid-hex").onFocusChanged {
                focused = it.isFocused
            },
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color =
                        if (validHexColor(text.text)) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.End,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
        )
    }
}

/** Asks before saving a colour dark enough to make text hard to read. */
@Composable
private fun ContrastWarning(onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("solid-contrast-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.issue_reminder)) },
        text = { Text(tr(R.string.solid_color_low_contrast)) },
        confirmButton = {
            TextButton(onSave, Modifier.testTag("solid-contrast-save")) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/** What the HEX field shows for [typed]: "#" and up to six upper-case hex digits. */
internal fun hexInput(typed: String) =
    ("#" + typed.uppercase().filter { it in "0123456789ABCDEF" }).take(7)
