// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite geometry and timing of one decorative effect.

package dev.pingclash.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val POUR_SECONDS = .9f
private val FringeCool = Color(0xFF5ADCFF)
private val FringeWarm = Color(0xFFFF78DC)

/** The colours one card's light is drawn with; made once per card and theme. */
internal class LiquidColors(val level: Color, val dark: Boolean, val breathes: Boolean) {
    /** Light inside the fill and on the surface. */
    val light = Color.White

    /** The surface line: light on dark glass, the level's colour, deepened, on light glass. */
    val line = if (dark) Color.White else lerp(level, Color.Black, .18f)

    /** On light glass the fill needs more colour to read as lit. */
    val strength = if (dark) 1f else 1.3f
}

/**
 * The selected card's battery as liquid light: it pours in to the level, its surface ripples and
 * leans with the phone, and a web of light moves in the fill. Stepped once a frame; nothing here is
 * saved or read by anything else.
 */
@Stable
internal class LiquidLight {
    private var width = 0f
    private var height = 0f
    private var unit = 1f
    private var lastNanos = 0L
    private var seconds = 0f
    private var pouredAt = -POUR_SECONDS
    private var tilt = 0f
    private var tiltSpeed = 0f
    private var level = 0f
    private var ripple = 1f
    private val body = Path()
    private val surface = Path()
    private val web = Path()

    /** Read in the draw phase only, so a frame redraws the card and recomposes nothing. */
    var frame by mutableLongStateOf(0L)
        private set

    /** Starts the pour: the light runs in from empty to its level. */
    fun pour() {
        pouredAt = seconds
    }

    private fun surfaceX(at: Float): Float =
        level +
            tilt * (at - height / 2f) * .42f +
            ripple *
                unit *
                (3.2f * sin(at / unit * .07f + seconds * 1.4f) +
                    1.6f * sin(at / unit * .19f - seconds * 2.2f))

    /**
     * Advances one frame. [fraction] is the data left, [dp] one dp in pixels and [lean] how far the
     * phone is tilted sideways, from -1 to 1.
     */
    fun step(nanos: Long, size: Size, fraction: Float, dp: Float, lean: Float) {
        val dt = if (lastNanos == 0L) 0f else min(.05f, (nanos - lastNanos) / 1e9f)
        lastNanos = nanos
        seconds += dt
        if (size.width <= 0f || size.height <= 0f) return
        width = size.width
        height = size.height
        unit = dp
        // A spring towards the phone's lean, so the surface overshoots and settles like liquid.
        tiltSpeed += ((lean - tilt) * 38f - tiltSpeed * 5.5f) * dt
        tilt += tiltSpeed * dt
        val poured = ((seconds - pouredAt) / POUR_SECONDS).coerceIn(0f, 1f)
        val eased = 1f - (1f - poured).pow(3) + sin(poured * PI.toFloat()) * .07f * (1f - poured)
        level = width * fraction * eased + tiltSpeed * unit * 5f
        ripple = 1f + 2.6f * (1f - poured) + min(2f, abs(tiltSpeed) * 1.2f)
        frame = nanos
    }

    private fun trace() {
        body.rewind()
        surface.rewind()
        body.moveTo(0f, 0f)
        var at = 0f
        var first = true
        while (at < height) {
            val edge = surfaceX(at)
            body.lineTo(edge, at)
            if (first) surface.moveTo(edge, at) else surface.lineTo(edge, at)
            first = false
            at += unit * 3f
        }
        val end = surfaceX(height)
        body.lineTo(end, height)
        surface.lineTo(end, height)
        body.lineTo(0f, height)
        body.close()
    }

    /** Everything, back to front. Call after [step] has run at least once for this size. */
    fun DrawScope.drawLiquid(colors: LiquidColors) {
        frame
        if (width <= 0f) return
        trace()
        val pulse = if (colors.breathes) .78f + .22f * sin(seconds * 2.1f) else 1f
        clipPath(body) { drawBody(colors, pulse) }
        drawSurface(colors)
    }

    private fun DrawScope.drawBody(colors: LiquidColors, pulse: Float) {
        drawRect(
            Brush.horizontalGradient(
                listOf(
                    colors.level.copy(alpha = (.40f * pulse * colors.strength).coerceAtMost(1f)),
                    colors.level.copy(alpha = (.58f * pulse * colors.strength).coerceAtMost(1f)),
                    lerp(colors.level, colors.light, .30f).copy(alpha = .86f * pulse),
                ),
                endX = level.coerceAtLeast(1f),
            )
        )
        // A web of light, as the sun draws on the floor of a pool.
        repeat(6) { row ->
            web.rewind()
            var at = 0f
            while (at <= level + unit * 30f) {
                val wave =
                    7f * sin(at / unit * .045f + seconds * (.7f + row * .13f) + row * 1.7f) +
                        4f * sin(at / unit * .11f - seconds * 1.1f + row)
                val webY = height * (row + .5f) / 6f + unit * wave
                if (at == 0f) web.moveTo(at, webY) else web.lineTo(at, webY)
                at += unit * 6f
            }
            drawPath(
                web,
                colors.light.copy(alpha = .07f + .03f * sin(seconds * .8f + row)),
                style = Stroke(unit),
                blendMode = BlendMode.Plus,
            )
        }
        repeat(3) { band ->
            val reach = width + unit * 160f
            val centre = (seconds * unit * (14f + band * 9f) + band * unit * 130f) % reach
            val x0 = centre - unit * 80f
            drawRect(
                Brush.linearGradient(
                    listOf(
                        Color.Transparent,
                        colors.light.copy(alpha = .10f + .03f * band),
                        Color.Transparent,
                    ),
                    start = Offset(x0 - unit * 40f, 0f),
                    end = Offset(x0 + unit * 40f, height * .5f),
                ),
                blendMode = BlendMode.Plus,
            )
        }
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, colors.light.copy(alpha = .50f * pulse)),
                // Narrower on a nearly empty battery, or its colour would wash out.
                startX = level - min(unit * 40f, level * .6f),
                endX = level + unit * 24f,
            ),
            blendMode = BlendMode.Plus,
        )
    }

    /** The surface: a colour fringe either side, as glass splits light, a halo and a thin line. */
    private fun DrawScope.drawSurface(colors: LiquidColors) {
        translate(left = unit * 1.6f) {
            drawPath(surface, FringeCool.copy(alpha = .55f), style = Stroke(unit * 2f))
        }
        translate(left = -unit * 1.6f) {
            drawPath(surface, FringeWarm.copy(alpha = .50f), style = Stroke(unit * 2f))
        }
        val halo = if (colors.dark) colors.light else colors.level
        val blend = if (colors.dark) BlendMode.Plus else BlendMode.SrcOver
        drawPath(surface, halo.copy(alpha = .08f), style = Stroke(unit * 10f), blendMode = blend)
        drawPath(surface, halo.copy(alpha = .22f), style = Stroke(unit * 5f), blendMode = blend)
        drawPath(surface, colors.line.copy(alpha = .96f), style = Stroke(unit * 1.4f))
    }
}
