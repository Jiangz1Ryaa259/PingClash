// SPDX-License-Identifier: GPL-3.0-only
// Colour channel ranges are fixed by the HEX/RGB/HSB definitions.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Robin's-egg blue, the first background preset.
internal const val DEFAULT_SOLID_COLOR = "#B1F0E9"
private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")

internal fun validHexColor(value: String) = HEX_COLOR.matches(value)

internal fun solidColor(hex: String): Color =
    Color((0xFF000000 or (hex.takeIf(::validHexColor) ?: DEFAULT_SOLID_COLOR).drop(1).toLong(16)))

/** The solid background with a gentle light from above, for a soft enveloping feel. */
internal fun solidBackdrop(color: Color) =
    Brush.verticalGradient(listOf(lerp(color, Color.White, .45f), color, color))

internal fun hexOf(color: Color) =
    "#%02X%02X%02X".format(channel(color.red), channel(color.green), channel(color.blue))

private fun channel(value: Float) = (value * 255).roundToInt().coerceIn(0, 255)

/** Hue in degrees, saturation and brightness 0-1. */
internal fun hsbOf(color: Color): FloatArray {
    val high = max(color.red, max(color.green, color.blue))
    val delta = high - min(color.red, min(color.green, color.blue))
    val hue =
        when {
            delta == 0f -> 0f
            high == color.red -> 60 * (((color.green - color.blue) / delta).mod(6f))
            high == color.green -> 60 * ((color.blue - color.red) / delta + 2)
            else -> 60 * ((color.red - color.green) / delta + 4)
        }
    return floatArrayOf(hue % 360f, if (high == 0f) 0f else delta / high, high)
}
