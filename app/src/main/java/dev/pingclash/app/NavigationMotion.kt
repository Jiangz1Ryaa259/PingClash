// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.collectLatest

@Stable
internal class NavigationMotion(private val drag: NavigationDragState, selected: Int) {
    val position = Animatable(selected.toFloat())
    val press = Animatable(0f)
    val glow = Animatable(0f)

    fun touch() = drag.touch

    fun visualPosition() = drag.position ?: position.value

    fun pressure() = press.value.coerceIn(0f, 1f)

    // Keep optics alive through the spring after a tap has already released.
    // Values are consumed in draw/layer callbacks, never once per frame in composition.
    fun travel() =
        if (drag.position != null) 0f
        else
            (abs(position.targetValue - position.value) * .32f + abs(position.velocity) * .07f)
                .coerceIn(0f, .85f)

    fun optics() = max(pressure(), travel())

    fun stretch() =
        if (drag.position != null) (abs(drag.velocity) * .04f).coerceAtMost(.18f) * pressure()
        else (abs(position.velocity) * .012f).coerceAtMost(.06f) * optics()

    fun scaleX() = 1f + .12f * pressure() + .035f * travel() + stretch()

    fun scaleY() = 1f + .08f * pressure() + .045f * travel() - stretch() * .35f
}

private const val TAP_SWELL_MILLIS = 110
private const val TAP_GLOW_MILLIS = 420

internal fun navigationPhysicalPosition(position: Float, rtl: Boolean) =
    if (rtl) 3f - position else position

@Composable
internal fun rememberNavigationMotion(
    drag: NavigationDragState,
    selected: Int,
    enabled: Boolean,
): NavigationMotion {
    val state = remember(drag) { NavigationMotion(drag, selected) }
    val current by rememberUpdatedState(selected)
    LaunchedEffect(state, enabled) {
        snapshotFlow { (drag.position != null) to (drag.position ?: current.toFloat()) }
            .collectLatest { (dragging, target) ->
                // Finger tracking is direct. Springs are reserved for release/tab changes.
                state.position.moveTo(target, enabled && !dragging, .78f, 430f)
            }
    }
    LaunchedEffect(state, enabled) {
        snapshotFlow { drag.pressed || drag.position != null }
            .collectLatest { pressed ->
                // A quick tap releases before the spring shows; finish a short swell
                // first so every tap visibly pulses the lens.
                if (!pressed && enabled && state.press.value < .8f)
                    state.press.animateTo(1f, tween(TAP_SWELL_MILLIS))
                state.press.moveTo(if (pressed && enabled) 1f else 0f, enabled, .78f, 550f)
            }
    }
    LaunchedEffect(state, enabled) {
        snapshotFlow { drag.touches }
            .collectLatest {
                if (it > 0 && enabled) {
                    state.glow.snapTo(1f)
                    state.glow.animateTo(0f, tween(TAP_GLOW_MILLIS, easing = FastOutSlowInEasing))
                } else state.glow.snapTo(0f)
            }
    }
    return state
}

private suspend fun Animatable<Float, AnimationVector1D>.moveTo(
    target: Float,
    enabled: Boolean,
    damping: Float,
    stiffness: Float,
) {
    if (enabled) animateTo(target, spring(damping, stiffness)) else snapTo(target)
}
