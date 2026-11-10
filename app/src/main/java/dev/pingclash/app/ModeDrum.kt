// SPDX-License-Identifier: GPL-3.0-only
// Geometry is a design value from the prototype, in prototype pixels scaled to the card.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import kotlin.math.PI
import kotlin.math.abs
import kotlinx.coroutines.delay

internal val ModeDrumRotation = SemanticsPropertyKey<Float>("ModeDrumRotation")
internal val ModeDrumSettlements = SemanticsPropertyKey<Int>("ModeDrumSettlements")

private const val TAP_SLOP = 12f
private const val TAP_MILLIS = 600L
// A phone card is about 330 units wide; one unit is at most 1.1 dp, so wide cards stay short.
private val MaxUnit = 1.1.dp
// Labels are in sp: a very narrow card must still leave them room.
private val MinHeight = 62.dp

/**
 * The home mode selector: the top of a large drum whose centre lies below the card, with the three
 * modes repeating round its rim and a fixed hairline marking the current one. Turn it with a finger
 * or a flick, or tap the left or right side to step one mode. The mode is requested once the drum
 * reaches it; a mode changed elsewhere, or a refused change, turns the drum back without a request.
 */
@Composable
internal fun ModeDrum(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = HomeModes.indexOfFirst { it.first == value }.coerceAtLeast(0)
    val motion = LocalMotionAllowed.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val drum = remember { ModeDrumMotion(index, scope) }
    val current by rememberUpdatedState(index)
    val change by rememberUpdatedState(onChange)
    var requested by remember { mutableStateOf<Int?>(null) }
    drum.onTick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    drum.onArrive = { notch ->
        val mode = drumMode(notch)
        if (mode != current) {
            modeFeedback(view)
            requested = mode
            change(HomeModes[mode].first)
        } else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    LaunchedEffect(index, enabled, drum.rests, drum.dragging, drum.moving, motion) {
        if (requested == index) requested = null
        if (!enabled || drum.dragging || drum.moving) return@LaunchedEffect
        // A request takes a moment to come back as the new mode; wait before turning back.
        if (requested != null) delay(800)
        withFrameNanos {}
        if (drumMode(drum.notch) != current) {
            requested = null
            drum.settleTo(current, motion)
        }
    }
    val labels = HomeModes.map { tr(it.second) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val unit = min(maxWidth / 330f, MaxUnit)
        Box(
            Modifier.fillMaxWidth()
                .height(max(unit * DRUM_HEIGHT, MinHeight))
                // The drum is far larger than its window; nothing may draw outside it.
                .clipToBounds()
                .selectableGroup()
                .testTag("mode-drum")
                .semantics {
                    this[ModeDrumRotation] = drum.rotation
                    this[ModeDrumSettlements] = drum.settlements
                }
                .drumGestures(drum, enabled, motion, unit)
        ) {
            DrumFace(drum, labels, enabled, unit)
            DrumTargets(drum, labels, enabled, motion)
        }
    }
}

/** Turning, flicking and tapping; one handler, so a slightly wobbly tap is still a tap. */
private fun Modifier.drumGestures(
    drum: ModeDrumMotion,
    enabled: Boolean,
    motion: Boolean,
    unit: Dp,
) =
    pointerInput(enabled, motion, unit) {
        if (!enabled) return@pointerInput
        val radius = (unit * DRUM_LABEL_RADIUS).toPx()
        val slop = TAP_SLOP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val gesture = DrumGesture(drum, motion, slop, radius, size.width.toFloat(), down)
            try {
                var pressed = true
                while (pressed) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    pressed = change != null && gesture.follow(change)
                }
            } finally {
                gesture.end()
            }
        }
    }

/** One touch on the drum: a tap unless it moves sideways past [slop], then a turn. */
private class DrumGesture(
    private val drum: ModeDrumMotion,
    private val motion: Boolean,
    private val slop: Float,
    private val radius: Float,
    private val width: Float,
    private val down: PointerInputChange,
) {
    private val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    private var turning = false
    private var lifted = false
    private var lastX = down.position.x

    private fun degrees(px: Float) = px / radius * 180f / PI.toFloat()

    /** Returns false once the finger has lifted. */
    fun follow(change: PointerInputChange): Boolean {
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!change.pressed) {
            lifted = true
            if (turning) drum.release(degrees(tracker.calculateVelocity().x), motion)
            else if (change.uptimeMillis - down.uptimeMillis < TAP_MILLIS) tap(change.position.x)
            return false
        }
        if (!turning && abs(change.position.x - down.position.x) > slop) {
            turning = true
            drum.beginDrag()
            lastX = change.position.x
        }
        if (turning) {
            drum.dragBy(degrees(change.position.x - lastX))
            lastX = change.position.x
            change.consume()
        }
        return true
    }

    /** The left and right 40% step one mode that way; the middle only acknowledges. */
    private fun tap(x: Float) {
        val share = x / width
        when {
            share < .4f -> drum.step(-1, motion)
            share > .6f -> drum.step(1, motion)
            else -> drum.emphasize()
        }
    }

    /** Ended without lifting (cancelled, or the drum was disabled): undo the turn. */
    fun end() {
        if (turning && !lifted) drum.cancelDrag(motion)
    }
}

/**
 * Accessibility and test targets over the drum: the mode to the left, the current mode and the mode
 * to the right, as radio buttons. Touches pass through to the drum's own handler.
 */
@Composable
private fun DrumTargets(
    drum: ModeDrumMotion,
    labels: List<String>,
    enabled: Boolean,
    motion: Boolean,
) {
    val centre = drum.logical
    Row(Modifier.fillMaxSize()) {
        listOf(-1 to .4f, 0 to .2f, 1 to .4f).forEach { (offset, share) ->
            val mode = drumMode(centre + offset)
            Box(
                Modifier.weight(share)
                    .fillMaxHeight()
                    .testTag("mode-${HomeModes[mode].first}")
                    .semantics {
                        role = Role.RadioButton
                        selected = offset == 0
                        contentDescription = labels[mode]
                        if (enabled)
                            onClick {
                                if (offset != 0) drum.step(offset, motion)
                                true
                            }
                    }
            )
        }
    }
}
