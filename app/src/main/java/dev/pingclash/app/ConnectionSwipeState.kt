// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // SwipeRow resistance, thresholds and animation tuning.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Physical left swipe, independent of text direction. Only a released full swipe commits. */
@Stable
internal class ConnectionSwipeState(
    private val scope: CoroutineScope,
    val actionWidth: Float,
    private val thresholdHaptic: () -> Unit,
    private val close: () -> Unit,
) {
    var width by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)
        private set

    var open by mutableStateOf(false)
        private set

    var armed by mutableStateOf(false)
        private set

    var committing by mutableStateOf(false)
        private set

    var motion = true
    val fold = Animatable(1f)
    private var raw = 0f
    private var settling: Job? = null

    fun start() {
        if (committing) return
        settling?.cancel()
        val exposed = -offset
        raw = if (exposed <= actionWidth) exposed else actionWidth + (exposed - actionWidth) / .55f
    }

    fun drag(delta: Float) {
        if (committing || width <= 0f) return
        raw = (raw - delta).coerceAtLeast(0f)
        val threshold = commitPoint()
        val knee = actionWidth + (threshold - actionWidth) / .55f
        val exposed =
            when {
                raw <= actionWidth -> raw
                raw <= knee -> actionWidth + (raw - actionWidth) * .55f
                else -> threshold + rubber(raw - knee, width)
            }
        offset = -exposed.coerceAtMost(width)
        val next = exposed >= threshold
        if (next && !armed) thresholdHaptic()
        armed = next
    }

    fun release(velocity: Float) {
        if (committing) return
        if (armed) commit()
        else {
            val shouldReveal =
                when {
                    velocity < -110f -> true
                    velocity > 110f -> false
                    else -> -offset > actionWidth / 2f
                }
            reveal(shouldReveal, velocity)
        }
    }

    fun cancel() {
        if (!committing) reveal(false)
    }

    fun reveal(value: Boolean, velocity: Float = 0f) {
        if (committing) return
        settling?.cancel()
        open = value
        armed = false
        settling =
            scope.launch { move(if (value) -actionWidth.coerceAtMost(width) else 0f, velocity) }
    }

    fun commit() {
        if (committing || width <= 0f) return
        settling?.cancel()
        committing = true
        armed = true
        open = false
        settling =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (motion) {
                        animate(offset, -width, animationSpec = tween(180)) { value, _ ->
                            offset = value
                        }
                        fold.animateTo(0f, tween(200))
                    } else {
                        offset = -width
                        fold.snapTo(0f)
                    }
                } finally {
                    // Navigation may dispose a row while its confirmed deletion folds shut.
                    close()
                }
            }
    }

    private suspend fun move(target: Float, velocity: Float) {
        if (!motion) offset = target
        else
            animate(
                offset,
                target,
                velocity.coerceIn(-1500f, 1500f),
                spring(if (kotlin.math.abs(velocity) >= 110f) .8f else 1f, 500f),
            ) { value, _ ->
                offset = value.coerceIn(-width, 0f)
            }
    }

    private fun commitPoint() = maxOf(width * .6f, actionWidth * 1.5f).coerceAtMost(width)

    private fun rubber(value: Float, dimension: Float) =
        value * dimension * .55f / (dimension + .55f * value)
}
