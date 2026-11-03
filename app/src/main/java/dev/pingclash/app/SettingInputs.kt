// SPDX-License-Identifier: GPL-3.0-only
// Stepper and field dimensions are the grouped-list design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The settings being edited. [change] applies at once (switches, choices, numbers); [edit] keeps
 * typed text in the draft until the page's Save button.
 */
internal class SettingsDraft(
    val value: ClientSettings,
    val enabled: Boolean,
    val change: (ClientSettings) -> Unit,
    val edit: (ClientSettings) -> Unit,
)

/** A number setting's name and bounds; [step] also rounds typed and set values. */
internal class NumberSpec(val title: String, val range: IntRange, val step: Int = 1)

/** A number setting with a range: accessibility services and tests can set it directly. */
private fun Modifier.numberSemantics(
    spec: NumberSpec,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
): Modifier {
    val range = spec.range
    val step = spec.step
    return testTag("number-${spec.title}").semantics {
        progressBarRangeInfo =
            ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat())
        setProgress { target ->
            if (!enabled) return@setProgress false
            val next = ((target / step).roundToInt() * step).coerceIn(range)
            if (next != value) onChange(next)
            true
        }
    }
}

/** How each number setting is changed: a few steps, a short list, or typed. */
internal enum class NumberInput {
    STEPPER,
    MENU,
    FIELD,
}

/** A number setting as one row, edited with [input]; [step] also rounds typed values. */
@Composable
internal fun NumberRow(
    spec: NumberSpec,
    value: Int,
    enabled: Boolean,
    input: NumberInput,
    onChange: (Int) -> Unit,
) {
    val title = spec.title
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 50.dp)
            .numberSemantics(spec, value, enabled, onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (input) {
            NumberInput.MENU -> {
                // Common values, plus the current one if it was set some other way.
                val options =
                    (menuValues(spec.range, spec.step) + value).distinct().sorted().map {
                        it.toString() to it.toString()
                    }
                Box(Modifier.weight(1f)) {
                    PopUpChoice(title, value.toString(), options, enabled) { onChange(it.toInt()) }
                }
            }
            NumberInput.STEPPER -> {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(value.toString(), color = Muted, style = MaterialTheme.typography.bodyLarge)
                Stepper(spec, value, enabled, onChange)
            }
            NumberInput.FIELD -> {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                NumberField(spec, value, enabled, onChange)
            }
        }
    }
}

/** About eight evenly spread values from [range], rounded to [step]. */
private fun menuValues(range: IntRange, step: Int): List<Int> {
    val span = range.last - range.first
    return (0..7).map { ((range.first + span * it / 7) / step * step).coerceIn(range) }.distinct()
}

/** The iOS stepper: a grey capsule split into minus and plus. */
@Composable
private fun Stepper(spec: NumberSpec, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val step = spec.step
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(PcIcon.Minus to -step, PcIcon.Add to step).forEachIndexed { index, (glyph, delta) ->
            val next = (value + delta).coerceIn(spec.range)
            val can = enabled && next != value
            if (index == 1)
                Box(
                    Modifier.width(.5.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            Box(
                Modifier.size(width = 46.dp, height = 32.dp)
                    .fluidClickable(can, depth = .9f) { onChange(next) }
                    .semantics {
                        contentDescription = "${spec.title} ${if (delta < 0) "-" else "+"}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                AppGlyph(
                    glyph,
                    Modifier.size(18.dp),
                    if (can) MaterialTheme.colorScheme.onSurface else Muted.copy(alpha = .5f),
                )
            }
        }
    }
}

/** A right-aligned number typed in place; it applies on Done or when focus leaves. */
@Composable
private fun NumberField(spec: NumberSpec, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
    fun commit() {
        val next =
            text.toIntOrNull()?.let { (it / spec.step * spec.step).coerceIn(spec.range) } ?: value
        text = next.toString()
        if (next != value) onChange(next)
    }
    BasicTextField(
        text,
        { text = it.filter(Char::isDigit).take(6) },
        Modifier.width(96.dp).onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
        enabled = enabled,
        singleLine = true,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = if (focused) MaterialTheme.colorScheme.onSurface else Muted,
                textAlign = TextAlign.End,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { done(focus) }),
    )
}

private fun done(focus: FocusManager) = focus.clearFocus()

/**
 * A text setting as one row: the name on the left and the value typed in place on the right, in
 * grey until focused. Empty shows [placeholder].
 */
@Composable
internal fun TextFieldRow(
    title: String,
    value: String,
    enabled: Boolean,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(.45f), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        Box(Modifier.weight(.55f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty() && !focused)
                Text(
                    placeholder,
                    color = Muted.copy(alpha = .6f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            BasicTextField(
                value,
                onChange,
                Modifier.fillMaxWidth()
                    .testTag("field-$title")
                    .semantics { contentDescription = title }
                    .onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = if (focused) MaterialTheme.colorScheme.onSurface else Muted,
                        textAlign = TextAlign.End,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done(focus) }),
            )
        }
    }
}

/** Plain text edited at full width inside a group, such as rule lists. */
@Composable
internal fun TextAreaRow(
    value: String,
    enabled: Boolean,
    tag: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) {
        if (value.isEmpty())
            Text(
                placeholder,
                Modifier.padding(vertical = 12.dp),
                color = Muted.copy(alpha = .6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth()
                .heightIn(min = 200.dp, max = 400.dp)
                .padding(vertical = 12.dp)
                .testTag(tag),
            enabled = enabled,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}
