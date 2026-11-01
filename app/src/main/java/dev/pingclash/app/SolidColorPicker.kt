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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** What the collapsible editor is doing: adding a colour, or changing [original]. */
private class SwatchEdit(val original: String?)

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
    var edit by remember { mutableStateOf<SwatchEdit?>(null) }
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
                if (longPress) edit = SwatchEdit(hex) else onApply(hex, null)
            },
            onToggle = { edit = if (edit == null) SwatchEdit(null) else null },
        )
    }
    edit?.let { current ->
        AlertDialog(
            modifier = Modifier.testTag("solid-color-dialog"),
            onDismissRequest = { edit = null },
            title = {
                Text(
                    tr(
                        if (current.original == null) R.string.palette_add
                        else R.string.palette_edit_color
                    )
                )
            },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    SwatchEditor(current, value, swatches, onApply) { edit = null }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ edit = null }) { Text(tr(R.string.cancel)) } },
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

/** Preview, HEX and RGB/HSB sliders with typeable values; applies only on Add/Save/Delete. */
@Composable
private fun SwatchEditor(
    edit: SwatchEdit,
    value: String,
    swatches: List<String>,
    onApply: (String?, List<String>?) -> Unit,
    onClose: () -> Unit,
) {
    var live by remember(edit) { mutableStateOf(solidColor(edit.original ?: value)) }
    var confirmContrast by remember(edit) { mutableStateOf(false) }
    fun applyColor() {
        val hex = hexOf(live)
        val next =
            if (edit.original == null) (swatches + hex).distinct().take(MAX_SOLID_SWATCHES)
            else swatches.map { if (it == edit.original) hex else it }.distinct()
        onApply(hex, next)
        onClose()
    }
    Column(
        Modifier.testTag("solid-color-editor"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SolidPreview(live)
        SolidHexField(hexOf(live)) { live = solidColor(it) }
        RgbSliders(live) { live = it }
        HsbSliders(live) { live = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val original = edit.original
            if (original != null && swatches.size > 1)
                TextButton(
                    {
                        onApply(null, swatches - original)
                        onClose()
                    },
                    Modifier.testTag("solid-color-delete"),
                ) {
                    Text(
                        tr(R.string.palette_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            Box(Modifier.weight(1f))
            Button(
                { if (live.luminance() < .45f) confirmContrast = true else applyColor() },
                Modifier.testTag("solid-color-save"),
            ) {
                Text(
                    tr(if (original == null) R.string.palette_add_action else R.string.palette_save)
                )
            }
        }
    }
    if (confirmContrast)
        AlertDialog(
            modifier = Modifier.testTag("solid-contrast-dialog"),
            onDismissRequest = { confirmContrast = false },
            title = { Text(tr(R.string.issue_reminder)) },
            text = { Text(tr(R.string.solid_color_low_contrast)) },
            confirmButton = {
                TextButton(
                    {
                        confirmContrast = false
                        applyColor()
                    },
                    Modifier.testTag("solid-contrast-save"),
                ) {
                    Text(tr(R.string.save))
                }
            },
            dismissButton = {
                TextButton({ confirmContrast = false }) { Text(tr(R.string.cancel)) }
            },
        )
}

@Composable
private fun SolidPreview(color: Color) {
    Box(
        Modifier.fillMaxWidth()
            .height(52.dp)
            .clip(UiCorners.Compact)
            .background(solidBackdrop(color))
            .border(.7.dp, MaterialTheme.colorScheme.outlineVariant, UiCorners.Compact),
        contentAlignment = Alignment.CenterStart,
    ) {
        val hex = hexOf(color)
        Text(
            swatchName(hex)?.let { tr(it) + "  ·  " + hex } ?: hex,
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (color.luminance() > .5f) Color(0xFF17212B) else Color.White,
        )
    }
}

@Composable
private fun SolidHexField(current: String, onValid: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(current, focused) { if (!focused) text = current }
    OutlinedTextField(
        text,
        { input ->
            val next = ("#" + input.uppercase().filter { it in "0123456789ABCDEF" }).take(7)
            text = next
            if (validHexColor(next)) onValid(next)
        },
        Modifier.fillMaxWidth().testTag("solid-hex").onFocusChanged { focused = it.isFocused },
        label = { Text(tr(R.string.solid_color_hex)) },
        singleLine = true,
        isError = !validHexColor(text),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
        shape = UiCorners.Compact,
    )
}

@Composable
private fun RgbSliders(color: Color, onColor: (Color) -> Unit) {
    listOf(
            Triple(R.string.color_red, color.red) { v: Float -> color.copy(red = v) },
            Triple(R.string.color_green, color.green) { v: Float -> color.copy(green = v) },
            Triple(R.string.color_blue, color.blue) { v: Float -> color.copy(blue = v) },
        )
        .forEach { (label, value, with) ->
            ChannelSlider(
                tr(label),
                value * 255,
                0f..255f,
                Brush.horizontalGradient(listOf(with(0f), with(1f))),
            ) {
                onColor(with(it / 255))
            }
        }
}

@Composable
private fun HsbSliders(color: Color, onColor: (Color) -> Unit) {
    val (hue, saturation, brightness) = hsbOf(color).toList()
    val of = { h: Float, s: Float, b: Float -> Color.hsv(h.coerceIn(0f, 359.9f), s, b) }
    ChannelSlider(
        tr(R.string.color_hue),
        hue,
        0f..360f,
        Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, .7f, 1f) }),
    ) {
        onColor(of(it, saturation, brightness))
    }
    ChannelSlider(
        tr(R.string.color_saturation),
        saturation * 100,
        0f..100f,
        Brush.horizontalGradient(listOf(of(hue, 0f, brightness), of(hue, 1f, brightness))),
    ) {
        onColor(of(hue, it / 100, brightness))
    }
    ChannelSlider(
        tr(R.string.color_brightness),
        brightness * 100,
        0f..100f,
        Brush.horizontalGradient(listOf(Color.Black, of(hue, saturation, 1f))),
    ) {
        onColor(of(hue, saturation, it / 100))
    }
}

/** A slider on a gradient track with its value in a box the user can also type into. */
@Composable
private fun ChannelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    track: Brush,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(44.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(10.dp)
                    .clip(UiCorners.Pill)
                    .background(track)
            )
            Slider(
                value.coerceIn(range),
                onChange,
                valueRange = range,
                colors =
                    SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
            )
        }
        ChannelNumber(value.roundToInt(), range, label, onChange)
    }
}

/** Typed values apply once in range; the box is not rewritten while it has focus. */
@Composable
private fun ChannelNumber(
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onChange: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
    BasicTextField(
        text,
        { input ->
            text = input.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.toFloat()?.takeIf { it in range }?.let(onChange)
        },
        Modifier.width(48.dp)
            .clip(UiCorners.Compact)
            .border(.7.dp, MaterialTheme.colorScheme.outlineVariant, UiCorners.Compact)
            .padding(vertical = 6.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
        singleLine = true,
        textStyle =
            MaterialTheme.typography.labelLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
