// SPDX-License-Identifier: GPL-3.0-only
// Gestures use the stable outer hit layer; dragging is not clipped to the card/stage.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

internal fun Modifier.lanyardAction(
    enabled: Boolean,
    label: String,
    description: String,
    action: () -> Unit,
) =
    semantics(mergeDescendants = true) {
            role = Role.Button
            stateDescription = description
            if (!enabled) disabled()
            onClick(label) {
                if (enabled) action()
                enabled
            }
        }
        .onKeyEvent { event ->
            val activation = event.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)
            if (enabled && activation && event.type == KeyEventType.KeyUp) {
                action()
                true
            } else false
        }
        .focusable(enabled)

private enum class CardPress {
    TAP,
    CANCEL,
}

/** A quick swipe yields to page scrolling; a stationary hold exclusively starts dragging. */
@Composable
internal fun Modifier.lanyardGestures(
    enabled: Boolean,
    moving: Boolean,
    density: Float,
    pose: LanyardPose,
    onTap: () -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val haptics = LocalHapticFeedback.current
    return pointerInput(enabled, moving) {
        if (enabled)
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    when (awaitCardPress(down)) {
                        CardPress.TAP -> tap()
                        CardPress.CANCEL -> Unit
                        null -> {
                            pose.drag = Offset.Zero
                            pose.holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    change.consume()
                                    if (moving)
                                        pose.drag =
                                            Offset(
                                                pose.drag.x + delta.x / density,
                                                pose.drag.y + delta.y / density,
                                            )
                                }
                            } finally {
                                pose.holding = false
                            }
                        }
                    }
                }
            } finally {
                pose.holding = false
                pose.drag = Offset.Zero
            }
    }
}

private suspend fun AwaitPointerEventScope.awaitCardPress(down: PointerInputChange): CardPress? =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var result: CardPress? = null
        while (result == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            result =
                when {
                    change == null -> CardPress.CANCEL
                    change.isConsumed -> CardPress.CANCEL
                    event.changes.count { it.pressed } > 1 -> CardPress.CANCEL
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop ->
                        CardPress.CANCEL
                    change.changedToUpIgnoreConsumed() -> CardPress.TAP
                    else -> null
                }
        }
        result
    }
