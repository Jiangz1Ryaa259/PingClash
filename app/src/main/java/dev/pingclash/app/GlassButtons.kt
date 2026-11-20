// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite sizes and tints of the buttons.

package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The action button: a capsule of light glass with dark text, no colour. A button that cannot act
 * yet is fainter glass, not a grey slab. Lights where it is touched.
 */
@Suppress("LongParameterList") // The same parameters as the Material component it replaces.
@Composable
internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = UiCorners.Pill,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val dark = glassDark()
    val ink = MaterialTheme.colorScheme.onSurface
    val fill =
        when {
            !enabled -> Color.White.copy(alpha = if (dark) .08f else .34f)
            dark -> Color.White.copy(alpha = .16f)
            else -> Color.White.copy(alpha = .80f)
        }
    Row(
        modifier
            .defaultMinSize(minHeight = 50.dp)
            .glassClickable(enabled, shape = shape, depth = .97f, onClick = onClick)
            .clip(shape)
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, fill)
                drawPath(
                    Path().apply { addOutline(outline) },
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (enabled && !dark) 1f else .35f),
                        .6f to Color.White.copy(alpha = 0f),
                    ),
                    style = Stroke(1.dp.toPx()),
                )
            }
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides ink.copy(alpha = if (enabled) 1f else .38f),
            LocalTextStyle provides
                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        ) {
            content()
        }
    }
}

/**
 * A quiet text action, as in dialogs and inline links: the label alone in the accent colour, a
 * capsule of light only while pressed.
 */
@Composable
internal fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .glassClickable(enabled, shape = UiCorners.Pill, depth = .96f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) accent else accent.copy(alpha = .38f),
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            content()
        }
    }
}
