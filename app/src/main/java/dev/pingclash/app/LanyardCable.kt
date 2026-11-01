// SPDX-License-Identifier: GPL-3.0-only
// Cable geometry and weave spacing are intentional physical design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val LocalLanyardCable = staticCompositionLocalOf<MutableState<Offset?>?> { null }
internal val CableEndY = SemanticsPropertyKey<Float>("CableEndY")

/** Root drawing starts at window y=0, independently of headers, insets and scroll. */
@Composable
internal fun WindowLanyardCable(endpoint: MutableState<Offset?>, hidden: Boolean = false) {
    val end = endpoint.value ?: return
    Canvas(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .testTag("window-lanyard-cable")
            .semantics { this[CableEndY] = end.y }
    ) {
        drawLanyardCable(end)
    }
}

@Composable
internal fun Modifier.lanyardAnchor(): Modifier {
    val cable = LocalLanyardCable.current
    val density = LocalDensity.current.density
    DisposableEffect(cable) { onDispose { cable?.value = null } }
    return this.then(
        remember(cable, density) {
            Modifier.onGloballyPositioned { coordinates ->
                val end = coordinates.localToRoot(Offset(coordinates.size.width / 2f, 8 * density))
                if (cable?.value != end) cable?.value = end
            }
        }
    )
}

@Composable
internal fun LocalLanyardStrap(x: Float, y: Float, modifier: Modifier) {
    if (LocalLanyardCable.current != null) return
    Canvas(modifier) { drawLanyardCable(Offset(size.width / 2 + x.dp.toPx(), (74 + y).dp.toPx())) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLanyardCable(end: Offset) {
    if (end.y <= 0f) return
    val top = size.width / 2
    val path =
        Path().apply {
            moveTo(top, -12.dp.toPx())
            cubicTo(top, end.y * .35f, end.x + (end.x - top) * .08f, end.y * .78f, end.x, end.y)
        }
    drawPath(
        path,
        Color.Black.copy(alpha = .27f),
        style = Stroke(17.dp.toPx(), cap = StrokeCap.Butt),
    )
    drawPath(
        path,
        Brush.horizontalGradient(
            listOf(Color(0xFF0A0D10), Color(0xFF41484C), Color(0xFF0B1013)),
            top - 20.dp.toPx(),
            top + 35.dp.toPx(),
        ),
        style = Stroke(13.dp.toPx()),
    )
    val weave = android.graphics.PathMeasure(path.asAndroidPath(), false)
    val point = FloatArray(2)
    val tangent = FloatArray(2)
    var position = 0f
    while (position < weave.length) {
        weave.getPosTan(position, point, tangent)
        val across = Offset(tangent[1], -tangent[0]) * 5.5.dp.toPx()
        val center = Offset(point[0], point[1])
        drawLine(Color.White.copy(alpha = .14f), center - across, center + across, .4.dp.toPx())
        position += 1.8.dp.toPx()
    }
}
