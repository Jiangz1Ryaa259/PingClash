// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite sizes, springs and timings of one effect.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The ⋮ (or any icon) that opens a menu: a plain icon that sinks a little and lights under the
 * finger, the light fading as it lets go; with its menu open it looks as it does at rest. Over the
 * page's scene the light is white; on a card or sheet, where white would not show, it is a soft
 * wash of the ink colour.
 */
@Composable
internal fun MenuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overScene: Boolean = false,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = LocalMotionAllowed.current
    val glow = remember { Animatable(0f) }
    val sink = remember { Animatable(1f) }
    LaunchedEffect(pressed, motion) {
        val target = if (pressed) 1f else 0f
        if (motion) glow.animateTo(target, spring(.8f, 420f)) else glow.snapTo(target)
    }
    LaunchedEffect(pressed, motion) {
        val target = if (pressed && motion) .86f else 1f
        if (motion) sink.animateTo(target, spring(.55f, 600f)) else sink.snapTo(target)
    }
    val dark = glassDark()
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(48.dp)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .drawBehind {
                val g = glow.value
                if (g <= 0f) return@drawBehind
                val (base, alpha) =
                    if (overScene) Color.White to g * (if (dark) .32f else .9f) else ink to g * .11f
                val radius = 29.dp.toPx() * (.6f + .4f * g)
                val light = base.copy(alpha = alpha)
                drawCircle(
                    Brush.radialGradient(
                        0f to light,
                        .62f to base.copy(alpha = alpha * .9f),
                        1f to base.copy(alpha = 0f),
                        center = center,
                        radius = radius,
                    ),
                    radius,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = sink.value
                scaleY = sink.value
            }
        ) {
            content()
        }
    }
}

/** The Proxies page's ⋮ and its menu, [content], over the page's scene. */
@Composable
internal fun RevealMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box {
        MenuIconButton({ onExpandedChange(!expanded) }, modifier, overScene = true) {
            ProxySymbol("menu", color = pageInk().text)
        }
        GlassMenu(expanded, { onExpandedChange(false) }, content = content)
    }
}
