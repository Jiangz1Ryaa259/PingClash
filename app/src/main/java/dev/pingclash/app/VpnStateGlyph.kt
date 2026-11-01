// SPDX-License-Identifier: GPL-3.0-only
// Shape coordinates, spring and rotation timings are visual design constants.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import kotlin.math.max
import kotlin.math.min

/** Keep the label legible at every intermediate background color, not just the endpoints. */
internal fun controlForeground(preferred: Color, background: Color): Color {
    val a = preferred.luminance()
    val b = background.luminance()
    return if ((max(a, b) + .05f) / (min(a, b) + .05f) >= 4.6f) preferred
    else if (b > .179f) Color.Black else Color.White
}

@Composable
internal fun VpnStateGlyph(phase: String, animate: Boolean, color: Color, diameter: Dp = 28.dp) {
    val working = phase == "connecting" || phase == "stopping"
    val progress =
        animateFloatAsState(
            if (working || phase == "established") 1f else 0f,
            if (animate) spring(.82f, 500f) else snap(),
            label = "vpn-shape",
        )
    val rotation: State<Float> =
        if (working && animate) {
            rememberInfiniteTransition(label = "vpn-working")
                .animateFloat(
                    0f,
                    360f,
                    infiniteRepeatable(tween(1600, easing = LinearEasing)),
                    label = "vpn-working-rotation",
                )
        } else remember { mutableStateOf(0f) }
    val morph = remember {
        Morph(
            RoundedPolygon.circle(numVertices = 12),
            RoundedPolygon.rectangle(width = 1.6f, height = 1.6f, rounding = CornerRounding(.28f)),
        )
    }
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val factor = (size.minDimension - 4.dp.toPx()) / 2
            withTransform({
                translate(size.width / 2, size.height / 2)
                rotate(rotation.value, Offset.Zero)
                scale(factor, factor, Offset.Zero)
            }) {
                drawPath(
                    morph.toPath(progress.value.coerceIn(0f, 1f)).asComposePath(),
                    color,
                    style = Stroke(1.5.dp.toPx() / factor),
                )
            }
        }
        AppGlyph(
            if (working) PcIcon.Bolt
            else if (phase == "established") PcIcon.Check else PcIcon.Power,
            Modifier.size(diameter / 2),
            color,
        )
    }
}
