// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared material tokens: optics are confined to backdrops, foreground stays sharp. */
@Immutable
internal data class NavigationGlassStyle(
    val tint: Color,
    val blur: Dp = 10.dp,
    val refractionHeight: Dp = 14.dp,
    val refractionAmount: Dp = 18.dp,
    val saturation: Float = 1.35f,
    val highlightAlpha: Float = .78f,
    val shadowAlpha: Float = .12f,
    val innerShadowAlpha: Float = .11f,
)

internal fun navigationGlassStyle(dark: Boolean) =
    NavigationGlassStyle(
        tint = if (dark) Color(0xFF10151B).copy(alpha = .32f) else Color.White.copy(alpha = .28f),
        highlightAlpha = if (dark) .65f else .90f,
        shadowAlpha = if (dark) .20f else .12f,
    )

internal fun navLabelColor(dark: Boolean) = if (dark) Color.White else Color.Black

// Deeper (light) and paler (dark) than the accent: the label must hold 4.5:1 on glass over any
// page.
internal fun navAccentColor(dark: Boolean) = if (dark) Color(0xFFA4D0FF) else Color(0xFF00377A)

// Concentrate legibility under the fixed labels while leaving the rim translucent.
internal fun navReadabilityTint(dark: Boolean) =
    if (dark) Color(0xFF10151B).copy(alpha = .72f) else Color.White.copy(alpha = .58f)

internal val LocalNavigationGlassStyle = staticCompositionLocalOf<NavigationGlassStyle?> { null }
