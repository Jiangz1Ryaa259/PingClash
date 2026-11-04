// SPDX-License-Identifier: GPL-3.0-only
// Backdrop 1.0.4 source dependency; optical kernel and layer lifecycle are upstream.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

internal const val NAV_FALLBACK_ALPHA = 1f
internal val NavBlurEnabled = SemanticsPropertyKey<Boolean>("NavBlurEnabled")
internal val NavOpticalBackend = SemanticsPropertyKey<String>("NavOpticalBackend")

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun Modifier.navGlassMaterial(
    style: NavigationGlassStyle,
    // The optical lens accepts corner-based shapes only (a plain rectangle throws).
    shape: CornerBasedShape = UiCorners.Pill,
): Modifier {
    val backdrop = LocalNavigationBackdrop.current
    val haze = LocalNavHaze.current
    val surface = MaterialTheme.colorScheme.surface
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val material =
        when {
            backdrop != null ->
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        colorControls(saturation = style.saturation)
                        blur(style.blur.toPx())
                        lens(style.refractionHeight.toPx(), style.refractionAmount.toPx())
                    },
                    highlight = { Highlight(width = 1.dp, alpha = style.highlightAlpha) },
                    shadow = { Shadow(radius = 18.dp, alpha = style.shadowAlpha) },
                    innerShadow = { InnerShadow(radius = 6.dp, alpha = style.innerShadowAlpha) },
                    onDrawSurface = { drawRect(style.tint) },
                )
            haze != null -> {
                val material =
                    HazeMaterials.thin(surface)
                        .copy(
                            blurRadius = style.blur,
                            noiseFactor = .005f,
                            tints = listOf(HazeTint(style.tint)),
                            fallbackTint = HazeTint(surface.copy(alpha = NAV_FALLBACK_ALPHA)),
                        )
                clip(shape).hazeEffect(haze, style = material)
            }
            else -> clip(shape).background(surface.copy(alpha = NAV_FALLBACK_ALPHA))
        }
    val veil = navReadabilityTint(dark)
    return material.drawBehind {
        // A soft material veil behind glyphs replaces per-letter outlines. A tall panel keeps it
        // even; a bar fades it at its top and bottom edges.
        if (shape != UiCorners.Pill) {
            drawRect(veil)
            return@drawBehind
        }
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                .16f to veil,
                .84f to veil,
                1f to Color.Transparent,
            )
        )
    }
}

@Composable
internal fun Modifier.navSelectorMaterial(
    style: NavigationGlassStyle,
    source: Backdrop?,
    motion: NavigationMotion,
    vertical: Boolean = false,
    offset: () -> Float,
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val activity = motion::optics
    // The lens stretches along the direction of travel: across the bar, down the sidebar.
    val transform: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
        if (vertical) {
            translationY = offset()
            scaleX = motion.scaleY()
            scaleY = motion.scaleX()
        } else {
            translationX = offset()
            scaleX = motion.scaleX()
            scaleY = motion.scaleY()
        }
    }
    if (source == null)
        return graphicsLayer(transform).clip(UiCorners.Pill).navSelectionWash(dark, activity)
    return drawBackdrop(
        backdrop = source,
        shape = { UiCorners.Pill },
        effects = {
            // Source already contains toolbar blur, tint and the accent row.
            // At rest k=0. Tap travel, held press and drag all introduce the lens.
            val k = activity()
            lens(14.dp.toPx() * k, 20.dp.toPx() * k, depthEffect = true, chromaticAberration = true)
        },
        layerBlock = transform,
        highlight = {
            Highlight(width = 1.dp, alpha = style.highlightAlpha * (.12f + .88f * activity()))
        },
        shadow = { Shadow(radius = 8.dp, alpha = style.shadowAlpha * activity()) },
        innerShadow = { InnerShadow(radius = 4.dp, alpha = style.innerShadowAlpha * activity()) },
        onDrawSurface = {
            val k = activity()
            drawRect(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .12f * k),
                        Color.Transparent,
                        Color.White.copy(alpha = .045f * k),
                    )
                )
            )
        },
    )
}

internal fun Modifier.navSelectionWash(dark: Boolean, press: () -> Float) = drawBehind {
    drawRect(
        navAccentColor(dark)
            .copy(alpha = (if (dark) .12f else .06f) * (1f - press().coerceIn(0f, 1f)))
    )
}
