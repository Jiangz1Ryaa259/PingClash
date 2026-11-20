// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite optical constants of one material.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The scene alone (sea or picture, without the pages), recorded for the pages' glass to blur. The
 * navigation's backdrop holds the pages too, so a panel sampling it would sample itself. Null where
 * live blur is off (Android 12 and older, reduced transparency, a protected page).
 */
internal val LocalSceneBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** The pages' glass corner: rounder than the old cards, as the current phone systems draw. */
internal val GlassCorner = RoundedCornerShape(26.dp)

/** Whether the theme is dark, read where a colour must differ. */
@Composable
internal fun glassDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < .5f

/**
 * Frosted glass for a page's panels: the scene behind blurred and brightened, a light rim along the
 * top edge and a soft shadow, no border. Without live blur it is a nearly solid frosted colour with
 * the same rim, so text stays readable over any scene. Clips its content to [shape].
 */
@Composable
internal fun Modifier.glassPanel(shape: Shape = GlassCorner): Modifier {
    val backdrop = LocalSceneBackdrop.current
    val dark = glassDark()
    val surface = MaterialTheme.colorScheme.surface
    val material =
        if (backdrop != null)
            drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    colorControls(saturation = 1.6f)
                    blur(26.dp.toPx())
                },
                highlight = { Highlight(width = 1.dp, alpha = if (dark) .45f else .95f) },
                shadow = { Shadow(radius = 24.dp, alpha = if (dark) .30f else .16f) },
                onDrawSurface = { drawRect(surface.copy(alpha = if (dark) .50f else .58f)) },
            )
        else
            background(surface.copy(alpha = if (LocalImmersiveScene.current) .88f else 1f), shape)
                .drawBehind { drawRim(shape, dark) }
    return material.clip(shape)
}

/** The light rim of a panel without live blur: bright at the top edge, gone by the middle. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRim(shape: Shape, dark: Boolean) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawPath(
        path,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) .35f else .95f),
            .5f to Color.White.copy(alpha = 0f),
        ),
        style = Stroke(1.dp.toPx()),
    )
}

/** A panel of glass holding a column, the shared card of every page. */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glassPanel().padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Light where the finger is: a soft glow grows under the press point and fades after release. It is
 * clipped to [shape], so it never shows past a rounded corner. Off when motion is reduced.
 */
@Composable
internal fun Modifier.touchLight(
    source: InteractionSource,
    shape: Shape = RectangleShape,
): Modifier {
    val motion = LocalMotionAllowed.current
    val dark = glassDark()
    var at by remember { mutableStateOf(Offset.Unspecified) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(source, motion) {
        var fade: Job? = null
        source.interactions.collect { interaction ->
            fade?.cancel()
            fade =
                when (interaction) {
                    is PressInteraction.Press -> {
                        at = interaction.pressPosition
                        launch { glow.animateTo(1f, if (motion) tween(80) else snap()) }
                    }
                    is PressInteraction.Release,
                    is PressInteraction.Cancel ->
                        launch { glow.animateTo(0f, if (motion) tween(480) else snap()) }
                    else -> null
                }
        }
    }
    if (!motion) return this
    return drawWithContent {
        drawContent()
        val strength = glow.value
        if (strength <= 0f || at == Offset.Unspecified) return@drawWithContent
        val radius = 130.dp.toPx()
        val light = Color.White.copy(alpha = (if (dark) .22f else .55f) * strength)
        val brush = Brush.radialGradient(listOf(light, light.copy(alpha = 0f)), at, radius)
        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> drawRect(brush)
            else -> clipPath(Path().apply { addOutline(outline) }) { drawRect(brush) }
        }
    }
}

/** A ripple-free click that sinks a little and lights where it is touched, inside [shape]. */
@Composable
internal fun Modifier.glassClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    shape: Shape = RectangleShape,
    depth: Float = .97f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return fluidPress(source, depth, dim = false)
        .touchLight(source, shape)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}
