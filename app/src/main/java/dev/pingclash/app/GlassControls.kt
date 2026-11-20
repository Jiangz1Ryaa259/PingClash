// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite sizes and tints of the controls.

package dev.pingclash.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A text field as a soft glass well: no outline, a little lighter while typing. */
@Suppress("LongParameterList") // The same parameters as the Material component it replaces.
@Composable
internal fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val fill = Color.White.copy(alpha = if (glassDark()) .10f else .55f)
    TextField(
        value,
        onValueChange,
        modifier,
        enabled = enabled,
        singleLine = singleLine,
        label = label,
        placeholder = placeholder,
        isError = isError,
        keyboardOptions = keyboardOptions,
        shape = shape,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = fill.copy(alpha = fill.alpha + .15f),
                unfocusedContainerColor = fill,
                disabledContainerColor = fill.copy(alpha = fill.alpha * .6f),
                errorContainerColor = fill,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = MaterialTheme.colorScheme.error,
            ),
    )
}

/** An on/off chip: bright glass when on, faint glass when off; lights where it is touched. */
@Composable
internal fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = glassDark()
    val fill =
        when {
            selected && dark -> Color.White.copy(alpha = .26f)
            selected -> Color.White.copy(alpha = .92f)
            dark -> Color.White.copy(alpha = .08f)
            else -> Color.White.copy(alpha = .40f)
        }
    Box(
        modifier
            .semantics { this.selected = selected }
            .height(36.dp)
            .glassClickable(role = Role.Checkbox, shape = UiCorners.Pill, onClick = onClick)
            .clip(UiCorners.Pill)
            .background(fill)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        label()
    }
}

/**
 * One choice of a few, as a capsule with a bright knob that slides to the chosen part, the way the
 * current phone systems draw it.
 */
@Composable
internal fun <T> GlassSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = glassDark()
    val motion = LocalMotionAllowed.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(UiCorners.Pill)
            .background(Color.White.copy(alpha = if (dark) .08f else .40f))
            .padding(3.dp)
    ) {
        val part = maxWidth / options.size.coerceAtLeast(1)
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val knob by
            animateDpAsState(
                part * index,
                if (motion) spring(.7f, 600f) else snap(),
                label = "segment-knob",
            )
        Box(
            Modifier.offset(x = knob)
                .width(part)
                .fillMaxHeight()
                .shadow(4.dp, UiCorners.Pill, clip = false)
                .clip(UiCorners.Pill)
                .background(Color.White.copy(alpha = if (dark) .28f else .94f))
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { (value, label) ->
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .testTag("segment-$label")
                        .semantics { this.selected = value == selected }
                        .glassClickable(enabled, Role.RadioButton, UiCorners.Pill) {
                            onChange(value)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color =
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (enabled) 1f else .38f
                            ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
