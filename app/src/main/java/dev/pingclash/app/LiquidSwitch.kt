// SPDX-License-Identifier: GPL-3.0-only
// Geometry, alpha and spring constants describe this control's visual design.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Passive visual: the whole settings row owns one switch action and its state. */
@Composable
internal fun LiquidSwitch(
    checked: Boolean,
    enabled: Boolean,
    interactions: MutableInteractionSource,
) {
    val motion = LocalMotionAllowed.current && rememberForeground()
    val pressed by interactions.collectIsPressedAsState()
    val target = if (checked) 1f else 0f
    val position =
        animateFloatAsState(
            target,
            if (motion) spring(dampingRatio = .78f, stiffness = 650f) else snap(),
            label = "switch-position",
        )
    val pressure = switchPressure(pressed, enabled, motion)
    val colors = MaterialTheme.colorScheme
    val track =
        animateColorAsState(
            if (checked) colors.primary else colors.surfaceVariant,
            if (motion) tween(160) else snap(),
            label = "switch-track",
        )
    val thumb =
        animateColorAsState(
            if (checked) colors.onPrimary else colors.surface,
            if (motion) tween(160) else snap(),
            label = "switch-thumb",
        )
    val border = if (checked) colors.primary else colors.outline
    val glyph = if (checked) colors.primary else colors.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(56.dp, 36.dp).clearAndSetSemantics {}) {
        val alpha = if (enabled) 1f else .38f
        val p = position.value.coerceIn(0f, 1f)
        val stretch =
            if (motion) (abs(target - p) * 5f + pressure.value.coerceIn(0f, 1f) * 5f) else 0f
        drawLiquidSwitch(
            p,
            stretch,
            checked,
            rtl,
            SwitchPaint(track.value, thumb.value, border, colors.outline, glyph, alpha),
        )
    }
}

@Composable
private fun switchPressure(pressed: Boolean, enabled: Boolean, motion: Boolean): State<Float> =
    animateFloatAsState(
        if (pressed && enabled && motion) 1f else 0f,
        if (motion) spring(.7f, 900f) else snap(),
        label = "switch-pressure",
    )

private data class SwitchPaint(
    val track: Color,
    val thumb: Color,
    val border: Color,
    val outline: Color,
    val glyph: Color,
    val alpha: Float,
)

private fun DrawScope.drawLiquidSwitch(
    p: Float,
    stretch: Float,
    checked: Boolean,
    rtl: Boolean,
    paint: SwitchPaint,
) {
    with(paint) {
        val width = (26 + stretch).dp.toPx()
        val height = (26 - stretch * .25f).dp.toPx()
        val travel = size.width - 34.dp.toPx()
        val center = 17.dp.toPx() + travel * (if (rtl) 1 - p else p)
        val origin = Offset(center - width / 2, (size.height - height) / 2)
        drawRoundRect(
            track.copy(alpha = alpha),
            Offset(0f, 2.dp.toPx()),
            Size(size.width, 32.dp.toPx()),
            CornerRadius(16.dp.toPx()),
        )
        drawRoundRect(
            border.copy(alpha = alpha),
            Offset(.5.dp.toPx(), 2.5.dp.toPx()),
            Size(size.width - 1.dp.toPx(), 31.dp.toPx()),
            CornerRadius(16.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
        drawRoundRect(
            thumb.copy(alpha = alpha),
            origin,
            Size(width, height),
            CornerRadius(height / 2),
        )
        drawRoundRect(
            outline.copy(alpha = alpha),
            origin,
            Size(width, height),
            CornerRadius(height / 2),
            style = Stroke(1.dp.toPx()),
        )
        val cy = size.height / 2
        if (checked) {
            drawLine(
                glyph.copy(alpha = alpha),
                Offset(center - 4.dp.toPx(), cy),
                Offset(center - 1.dp.toPx(), cy + 3.dp.toPx()),
                1.6.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(
                glyph.copy(alpha = alpha),
                Offset(center - 1.dp.toPx(), cy + 3.dp.toPx()),
                Offset(center + 4.dp.toPx(), cy - 3.dp.toPx()),
                1.6.dp.toPx(),
                StrokeCap.Round,
            )
        } else
            drawLine(
                glyph.copy(alpha = alpha),
                Offset(center - 3.dp.toPx(), cy),
                Offset(center + 3.dp.toPx(), cy),
                1.6.dp.toPx(),
                StrokeCap.Round,
            )
    }
}
