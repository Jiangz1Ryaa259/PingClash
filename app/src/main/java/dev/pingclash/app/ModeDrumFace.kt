// SPDX-License-Identifier: GPL-3.0-only
// Geometry is a design value from the prototype, in prototype pixels scaled to the card.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The drum's height and its label circle, in units of the card (about 330 wide). */
internal const val DRUM_HEIGHT = 66f

internal const val DRUM_LABEL_RADIUS = 560f
private const val DRUM_RADIUS = 590f
private const val VISIBLE = 21f
private const val FADE = 7f
private const val LABEL_MARGIN = 6f

/** The band: its gradient (ends, middle) and its three rim lines. */
private class BandInk(
    val edge: Color,
    val middle: Color,
    val rimLight: Color,
    val rimDark: Color,
    val rimInner: Color,
)

/** The marks on the rim: the knurl's two strokes and the scale ticks. */
private class MarkInk(val knurlDark: Color, val knurlLight: Color, val tick: Color)

/** Labels: neighbours from far to near, the current mode moving and at rest. */
private class TextInk(val far: Color, val near: Color, val moving: Color, val accent: Color)

private class DrumInk(val band: BandInk, val marks: MarkInk, val text: TextInk)

@Composable
private fun drumInk(): DrumInk {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    return if (dark)
        DrumInk(
            BandInk(
                Color(0xFF22262C),
                Color(0xFF353A42),
                Color.White.copy(alpha = .14f),
                Color(0xFF14171B),
                Color(0xFF1B1E23),
            ),
            MarkInk(
                Color.Black.copy(alpha = .5f),
                Color.White.copy(alpha = .16f),
                Color(0xFFC9CED6),
            ),
            TextInk(Color(0xFF8A919B), Color(0xFFD3D8DF), scheme.onSurface, scheme.primary),
        )
    else
        DrumInk(
            BandInk(
                Color(0xFFD5DAE0),
                Color(0xFFE6E9EE),
                Color.White.copy(alpha = .9f),
                Color(0xFFA3ABB4),
                Color(0xFFCDD2D8),
            ),
            MarkInk(
                Color(0xFF8F98A2).copy(alpha = .6f),
                Color.White.copy(alpha = .9f),
                Color(0xFF3A3D42),
            ),
            TextInk(Color(0xFF707680), Color(0xFF464B54), scheme.onSurface, scheme.primary),
        )
}

@Composable
internal fun DrumFace(drum: ModeDrumMotion, labels: List<String>, enabled: Boolean, unit: Dp) {
    val ink = drumInk()
    val measurer = rememberTextMeasurer()
    val chosen = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val other = TextStyle(fontSize = 13.sp)
    Canvas(
        Modifier.fillMaxSize()
            // The band's lower edge fades into the card rather than ending in a straight cut.
            // Only the band: the labels drawn over it stay crisp.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        .7f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    ) {
        drawBand(DrumGeometry(size.width / 2, unit.toPx()), ink)
    }
    Canvas(Modifier.fillMaxSize()) {
        val u = unit.toPx()
        val geometry = DrumGeometry(size.width / 2, u)
        val alpha = if (enabled) 1f else .45f
        drawKnurl(geometry, drum.rotation, ink)
        drawTicks(geometry, drum.rotation, ink, alpha)
        drawLabels(geometry, drum, labels, LabelStyle(ink, measurer, chosen, other), alpha)
        drawHairline(geometry, ink.text.accent.copy(alpha = alpha), drum.pulse.value)
    }
}

private class DrumGeometry(val cx: Float, val u: Float) {
    val radius = DRUM_RADIUS * u
    val cy = (DRUM_RADIUS + 4) * u
    val labelRadius = DRUM_LABEL_RADIUS * u

    fun at(degrees: Float, r: Float): Offset {
        val t = degrees * PI.toFloat() / 180
        return Offset(cx + r * sin(t), cy - r * cos(t))
    }
}

private class LabelStyle(
    val ink: DrumInk,
    val measurer: TextMeasurer,
    val chosen: TextStyle,
    val other: TextStyle,
)

private fun fade(s: Float): Float {
    val d = abs(s)
    return if (d <= VISIBLE - FADE) 1f else (1 - (d - (VISIBLE - FADE)) / FADE).coerceIn(0f, 1f)
}

private fun DrawScope.drawBand(g: DrumGeometry, ink: DrumInk) {
    val centre = Offset(g.cx, g.cy)
    drawCircle(
        Brush.horizontalGradient(listOf(ink.band.edge, ink.band.middle, ink.band.edge)),
        g.radius - 24 * g.u,
        centre,
        style = Stroke(48 * g.u),
    )
    drawCircle(ink.band.rimLight, g.radius - g.u, centre, style = Stroke(1.4f * g.u))
    drawCircle(ink.band.rimDark, g.radius - 8 * g.u, centre, style = Stroke(g.u))
    drawCircle(ink.band.rimInner, g.radius - 48 * g.u, centre, style = Stroke(g.u))
}

private fun DrawScope.drawKnurl(g: DrumGeometry, rotation: Float, ink: DrumInk) {
    val first = ceil((-VISIBLE - 30 - rotation) / .9f).toInt()
    val last = floor((VISIBLE + 30 - rotation) / .9f).toInt()
    for (i in first..last) {
        val s = i * .9f + rotation
        drawLine(
            if (i % 2 == 0) ink.marks.knurlDark else ink.marks.knurlLight,
            g.at(s, g.radius - 2 * g.u),
            g.at(s, g.radius - 7 * g.u),
            g.u,
        )
    }
}

private fun DrawScope.drawTicks(g: DrumGeometry, rotation: Float, ink: DrumInk, alpha: Float) {
    val step = DRUM_SPACING / 8
    val first = ceil((-VISIBLE - rotation) / step).toInt()
    val last = floor((VISIBLE - rotation) / step).toInt()
    for (n in first..last) {
        val s = n * step + rotation
        val major = n % 8 == 0
        val length = if (major) 8 else if (n % 4 == 0) 5 else 3
        drawLine(
            ink.marks.tick.copy(alpha = (if (major) .7f else .35f) * fade(s) * alpha),
            g.at(s, g.radius - 9 * g.u),
            g.at(s, g.radius - (9 + length) * g.u),
            (if (major) 2f else 1f) * g.u,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawLabels(
    g: DrumGeometry,
    drum: ModeDrumMotion,
    labels: List<String>,
    style: LabelStyle,
    alpha: Float,
) {
    val settled = !drum.moving && !drum.dragging
    val current = if (settled || !drum.arrived) drum.notch else drum.goal
    val first = ceil((-VISIBLE - drum.rotation) / DRUM_SPACING).toInt()
    val last = floor((VISIBLE - drum.rotation) / DRUM_SPACING).toInt()
    for (k in first..last) {
        val s = k * DRUM_SPACING + drum.rotation
        val on = k == current
        val closeness = 1 - (abs(s) / DRUM_SPACING).coerceAtMost(1f)
        val color =
            when {
                on && (settled || drum.arrived) -> style.ink.text.accent
                on -> style.ink.text.moving
                else -> lerp(style.ink.text.far, style.ink.text.near, closeness)
            }
        val text =
            style.measurer.measure(labels[drumMode(k)], if (on) style.chosen else style.other)
        val at = g.at(s, g.labelRadius)
        // A label is tilted with the wheel, so it reaches further than half its width.
        val tilt = Math.toRadians(s.toDouble())
        val halfExtent =
            (text.size.width * abs(cos(tilt)) + text.size.height * abs(sin(tilt))).toFloat() / 2
        val p = at.copy(x = drumLabelX(at.x, halfExtent, size.width, LABEL_MARGIN * g.u))
        rotate(s, p) {
            drawText(
                text,
                color.copy(alpha = color.alpha * (if (on) 1f else .9f) * fade(s) * alpha),
                Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f),
            )
        }
    }
}

/**
 * Where a label centred at [x] goes so that it stays on the card. Most never move; a long
 * translation comes in just far enough to be read whole before it fades out.
 */
internal fun drumLabelX(x: Float, halfExtent: Float, width: Float, margin: Float): Float {
    val low = margin + halfExtent
    val high = width - margin - halfExtent
    return if (low < high) x.coerceIn(low, high) else x
}

private fun DrawScope.drawHairline(g: DrumGeometry, color: Color, pulse: Float) {
    drawLine(
        color,
        Offset(g.cx, 3 * g.u),
        // Down through the ticks, stopping above the current mode's label.
        Offset(g.cx, 23 * g.u),
        (2.5f + pulse * 2.2f).dp.toPx(),
        StrokeCap.Round,
    )
    val pointer =
        Path().apply {
            moveTo(g.cx - 6 * g.u, g.u)
            lineTo(g.cx + 6 * g.u, g.u)
            lineTo(g.cx, 8 * g.u)
            close()
        }
    drawPath(pointer, color)
}
