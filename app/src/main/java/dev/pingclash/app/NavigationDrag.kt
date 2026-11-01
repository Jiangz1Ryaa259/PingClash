// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.roundToInt

internal data class NavigationDragGeometry(
    val cellWidth: Float,
    val rtl: Boolean,
    val selected: Int,
)

@Stable
internal class NavigationDragState {
    var position by mutableStateOf<Float?>(null)
    var pressed by mutableStateOf(false)
    var velocity by mutableFloatStateOf(0f)
    // Last touch point and a counter per touch-down, for the tap light.
    var touch by mutableStateOf<Offset?>(null)
    var touches by mutableIntStateOf(0)
}

@Composable
internal fun Modifier.navigationDrag(
    geometry: NavigationDragGeometry,
    drag: NavigationDragState,
    onSelect: (Int) -> Unit,
): Modifier {
    val selected by rememberUpdatedState(geometry.selected)
    val select by rememberUpdatedState(onSelect)
    val foreground = rememberForeground()
    LaunchedEffect(foreground) {
        if (!foreground) {
            drag.position = null
            drag.pressed = false
            drag.velocity = 0f
        }
    }
    return trackNavigationPress(drag, foreground).pointerInput(
        geometry.cellWidth,
        geometry.rtl,
        foreground,
    ) {
        val velocity = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = {
                velocity.resetTracking()
                drag.velocity = 0f
                drag.position = selected.toFloat()
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                drag.velocity =
                    (velocity.calculateVelocity().x / geometry.cellWidth).coerceIn(-30f, 30f)
                val delta = amount / geometry.cellWidth * (if (geometry.rtl) -1 else 1)
                drag.position = ((drag.position ?: selected.toFloat()) + delta).coerceIn(0f, 3f)
            },
            onDragCancel = {
                drag.position = null
                drag.velocity = 0f
            },
            onDragEnd = {
                val target = (drag.position ?: selected.toFloat()).roundToInt().coerceIn(0, 3)
                drag.position = null
                drag.velocity = 0f
                if (target != selected) select(target)
            },
        )
    }
}

/** Observes, never consumes, touches on the bar: press state, touch point and count. */
private fun Modifier.trackNavigationPress(drag: NavigationDragState, foreground: Boolean) =
    pointerInput(foreground) {
        if (foreground)
            awaitEachGesture {
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                drag.touch = down.position
                drag.touches++
                drag.pressed = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.pressed }?.let { drag.touch = it.position }
                    } while (event.changes.any { it.pressed })
                } finally {
                    drag.pressed = false
                }
            }
    }
