// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * Press feedback after Apple's buttons: the control sinks quickly under the finger and dims a
 * little, then springs back with a soft overshoot when released. No ripple.
 */
@Composable
internal fun Modifier.fluidPress(
    source: MutableInteractionSource,
    depth: Float = .95f,
    dim: Boolean = true,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val motion = LocalMotionAllowed.current
    val scale by
        animateFloatAsState(
            if (pressed) depth else 1f,
            when {
                !motion -> snap()
                pressed -> spring(1f, 1400f)
                else -> spring(.52f, 520f)
            },
            label = "fluid-press",
        )
    val fade by
        animateFloatAsState(
            if (pressed && dim) .82f else 1f,
            if (motion) spring(1f, 900f) else snap(),
            label = "fluid-dim",
        )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = fade
    }
}

/** A ripple-free click with [fluidPress] feedback. */
@Composable
internal fun Modifier.fluidClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    depth: Float = .95f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return fluidPress(source, depth)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}
