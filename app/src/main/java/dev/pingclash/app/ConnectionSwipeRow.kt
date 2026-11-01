// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Native SwipeRow dimensions and visual tuning.

package dev.pingclash.app

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ConnectionSwipeRow(id: Long, onClose: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val view = LocalView.current
    val actionPixels = with(LocalDensity.current) { 80.dp.toPx() }
    val state =
        remember(id, actionPixels) {
            ConnectionSwipeState(
                scope,
                actionPixels,
                { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                { close() },
            )
        }
    val motion = LocalMotionAllowed.current
    SideEffect { state.motion = motion }
    val label = tr(R.string.close_connection)
    val description =
        when {
            state.armed -> tr(R.string.connection_release_delete)
            state.open -> tr(R.string.connection_delete_revealed)
            else -> ""
        }
    Box(
        Modifier.fillMaxWidth()
            .testTag("connection-$id")
            .onSizeChanged { state.width = it.width.toFloat() }
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * state.fold.value).roundToInt()) {
                    placeable.place(0, 0)
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .swipeConnection(state)
            .connectionKeys(state)
            .semantics {
                stateDescription = description
                customActions =
                    listOf(
                        CustomAccessibilityAction(label) {
                            state.commit()
                            true
                        }
                    )
            }
    ) {
        DeleteRail(state, id)
        Box(Modifier.graphicsLayer { translationX = state.offset }) { content() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.DeleteRail(
    state: ConnectionSwipeState,
    id: Long,
) {
    val spread by
        animateFloatAsState(
            if (state.armed) 1f else 0f,
            if (LocalMotionAllowed.current) spring(.85f, 600f) else snap(),
            label = "connection-delete-spread",
        )
    val red = MaterialTheme.colorScheme.error
    val ink = MaterialTheme.colorScheme.onError
    Box(
        Modifier.matchParentSize()
            .then(if (state.open) Modifier else Modifier.clearAndSetSemantics {})
            .drawBehind {
                val exposed = (-state.offset).coerceIn(0f, size.width)
                val width = exposed + (size.width - exposed) * spread
                drawRect(red, Offset(size.width - width, 0f), Size(width, size.height))
            }
    ) {
        Column(
            Modifier.align(AbsoluteAlignment.CenterRight)
                .width(80.dp)
                .fillMaxHeight()
                .testTag("connection-delete-$id")
                .fluidClickable(enabled = state.open && !state.committing, onClick = state::commit)
                .graphicsLayer {
                    translationX =
                        -spread * ((-state.offset - state.actionWidth).coerceAtLeast(0f) / 2f)
                    alpha = (-state.offset / state.actionWidth).coerceIn(0f, 1f)
                },
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(R.drawable.ic_connection_delete),
                null,
                Modifier.size(20.dp),
                tint = ink,
            )
            Text(tr(R.string.delete), color = ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Modifier.swipeConnection(state: ConnectionSwipeState) =
    pointerInput(state) {
        val tracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = {
                state.start()
                tracker.resetTracking()
            },
            onDragCancel = state::cancel,
            onDragEnd = { state.release(tracker.calculateVelocity().x) },
        ) { change, delta ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            state.drag(delta)
        }
    }

private fun Modifier.connectionKeys(state: ConnectionSwipeState) =
    onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else
                when (it.key) {
                    Key.DirectionLeft -> {
                        state.reveal(true)
                        true
                    }
                    Key.DirectionRight,
                    Key.Escape -> {
                        state.reveal(false)
                        true
                    }
                    Key.Delete,
                    Key.Backspace ->
                        if (state.open) {
                            state.commit()
                            true
                        } else false
                    else -> false
                }
        }
        .focusable()
