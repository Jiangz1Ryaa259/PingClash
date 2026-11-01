// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal val HomeModes =
    listOf(
        "rule" to R.string.rules,
        "global" to R.string.ui_mode_global_short,
        "direct" to R.string.direct,
    )

// Detent angles of the three positions, degrees clockwise from the top.
// Wide apart, so Rules and Direct sit well clear of Global and each other.
internal val ModeDialAngle = SemanticsPropertyKey<Float>("ModeDialAngle")
internal val ModeDialSettlements = SemanticsPropertyKey<Int>("ModeDialSettlements")
private const val TRAVEL = 90f
private val DialWidth = 140.dp
private val DialHeight = 106.dp
private val KnobSize = 70.dp
private val LabelRadius = 53.dp

private fun nearestDetent(angle: Float) = ModeDetents.indices.minBy { abs(ModeDetents[it] - angle) }

/**
 * A rotary mode selector, like a car's drive-mode dial: turn the knob (or tap a label) and it
 * clicks into Rules, Global or Direct. The mode only changes when the knob is let go, so brushing
 * it never reloads the routing; each position gives a detent tick.
 */
@Composable
internal fun ModeDial(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = HomeModes.indexOfFirst { it.first == value }.coerceAtLeast(0)
    val motion = LocalMotionAllowed.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(index)
    val change by rememberUpdatedState(onChange)
    val dial = remember { ModeDialMotion(index, scope) }
    val available by rememberUpdatedState(enabled)
    val settle: (Int) -> Unit = { target ->
        if (target != current) {
            modeFeedback(view)
            change(HomeModes[target].first)
        }
        dial.release()
    }
    LaunchedEffect(index, enabled, dial.dragging, dial.revision, motion) {
        if (!dial.dragging && enabled) {
            // Let the request publish its busy/committed state before choosing
            // a destination. An unchanged mode (rejection) is also reconciled.
            withFrameNanos {}
            if (!dial.dragging && available) dial.reconcile(current, motion)
        }
    }
    Box(
        modifier.size(DialWidth, DialHeight).selectableGroup().testTag("mode-dial").semantics {
            this[ModeDialAngle] = dial.angle.value
            this[ModeDialSettlements] = dial.settlements
        }
    ) {
        HomeModes.forEachIndexed { i, (id, label) ->
            DialLabel(
                tr(label),
                i == index,
                enabled,
                Modifier.testTag("mode-$id").arcPlacement(ModeDetents[i], LabelRadius),
            ) {
                settle(i)
            }
        }
        Knob(
            dial.angle.value,
            enabled,
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .size(KnobSize)
                .clearAndSetSemantics {
                    this[androidx.compose.ui.semantics.SemanticsProperties.TestTag] = "mode-knob"
                }
                .dialTurns(
                    enabled,
                    angle = { dial.angle.value },
                    onTurn = dial::drag,
                    onTick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                ) { released ->
                    settle(released ?: current)
                },
        )
    }
}

// Turning: follows the finger around the hub (sideways travel near the hub, where the angle
// is unstable), ticks at each detent and reports the nearest detent on release (null when
// the gesture was cancelled).
private fun Modifier.dialTurns(
    enabled: Boolean,
    angle: () -> Float,
    onTurn: (Float) -> Unit,
    onTick: () -> Unit,
    onRelease: (Int?) -> Unit,
) =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val center = Offset(size.width / 2f, size.height / 2f)
        var previous = 0f
        var detent = 0
        var engaged = false
        try {
            detectDragGestures(
                onDragStart = { start ->
                    engaged = true
                    previous = polar(start, center.x, center.y)
                    detent = nearestDetent(angle())
                },
                onDragEnd = {
                    engaged = false
                    onRelease(nearestDetent(angle()))
                },
                onDragCancel = {
                    engaged = false
                    onRelease(null)
                },
            ) { pointer, drag ->
                pointer.consume()
                val now = polar(pointer.position, center.x, center.y)
                val turn =
                    if ((pointer.position - center).getDistance() < size.width * .18f) drag.x * .6f
                    else wrap(now - previous)
                previous = now
                val next = (angle() + turn).coerceIn(-TRAVEL, TRAVEL)
                onTurn(next)
                val near = nearestDetent(next)
                if (near != detent) {
                    detent = near
                    onTick()
                }
            }
        } finally {
            if (engaged) onRelease(null)
        }
    }

private fun polar(point: Offset, cx: Float, cy: Float) =
    (atan2(point.x - cx, cy - point.y) * 180f / PI).toFloat()

private fun wrap(degrees: Float) = ((degrees + 540f) % 360f) - 180f

/** A label on the arc above the knob; it is the accessible radio button for its mode. */
@Composable
private fun DialLabel(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Text(
        text,
        modifier
            .fluidPress(source, .9f)
            .selectable(selected, source, null, enabled, Role.RadioButton, onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color =
            when {
                !enabled -> Muted.copy(alpha = .5f)
                selected -> MaterialTheme.colorScheme.onSurface
                else -> Muted
            },
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
    )
}

// Centres a child on the arc around the knob's hub (the dial box's lower centre).
private fun Modifier.arcPlacement(degrees: Float, radius: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val hubX = DialWidth.toPx() / 2f
    val hubY = DialHeight.toPx() - 4.dp.toPx() - KnobSize.toPx() / 2f
    val radians = degrees * PI / 180.0
    val x = hubX + radius.toPx() * sin(radians).toFloat() - placeable.width / 2f
    val y = hubY - radius.toPx() * cos(radians).toFloat() - placeable.height / 2f
    layout(placeable.width, placeable.height) { placeable.place(x.roundToInt(), y.roundToInt()) }
}

/**
 * Machined knob: a knurled rim and pointer that turn, under a sheen that stays put (the light does
 * not rotate with the knob), around a slightly concave cap.
 */
@Composable
private fun Knob(angle: Float, enabled: Boolean, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val body =
        if (dark) listOf(Color(0xFF4A5A60), Color(0xFF1B272C))
        else listOf(Color(0xFFFFFFFF), Color(0xFFD5DAE0))
    val ink = MaterialTheme.colorScheme.onSurface
    val ridge = if (dark) Color(0x59000000) else Color(0x24000000)
    val lit = Color.White.copy(alpha = if (dark) .10f else .7f)
    val veil = MaterialTheme.colorScheme.surface.copy(alpha = .5f)
    // Always opaque: a translucent knob let its elevation shadow show through, and Android
    // tessellates that shadow into visible polygons. Disabled is a veil drawn on top instead.
    Canvas(modifier.shadow(if (enabled) 6.dp else 3.dp, CircleShape)) {
        val r = size.minDimension / 2f
        drawCircle(Brush.linearGradient(body, Offset.Zero, Offset(size.width, size.height)), r)
        rotate(angle) { drawKnurl(r, ridge, lit) }
        drawSheen(r)
        drawCircle(
            Brush.linearGradient(body.reversed(), Offset.Zero, Offset(size.width, size.height)),
            r * .7f,
        )
        drawCircle(ridge, r * .7f, style = Stroke(1.dp.toPx()))
        drawCircle(
            Brush.radialGradient(
                // Fade to transparent white: fading to Color.Transparent (black) leaves grey bands.
                listOf(
                    Color.White.copy(alpha = if (dark) .16f else .55f),
                    Color.White.copy(alpha = 0f),
                ),
                center + Offset(-r * .25f, -r * .3f),
                r * .6f,
            ),
            r * .7f,
        )
        rotate(angle) {
            drawLine(
                ink,
                center + Offset(0f, -r * .6f),
                center + Offset(0f, -r * .34f),
                3.dp.toPx(),
                StrokeCap.Round,
            )
        }
        if (!enabled) drawCircle(veil, r)
    }
}

// Fine machined ridges: a hairline of shadow beside a hairline of light.
private fun DrawScope.drawKnurl(r: Float, shadow: Color, light: Color) {
    val ridges = 60
    val width = .8.dp.toPx()
    for (i in 0 until ridges) {
        for ((offset, color) in listOf(0.0 to shadow, .35 to light)) {
            val a = (i + offset) * 2.0 * PI / ridges
            val direction = Offset(sin(a).toFloat(), -cos(a).toFloat())
            drawLine(color, center + direction * (r * .82f), center + direction * (r * .96f), width)
        }
    }
}

// Two soft highlights fixed to the light, swept around the rim. Every stop is white, so the
// fades never pass through grey (which showed as dark wedges).
private fun DrawScope.drawSheen(r: Float) {
    val clear = Color.White.copy(alpha = 0f)
    val shine = Color.White.copy(alpha = .5f)
    val soft = Color.White.copy(alpha = .22f)
    drawCircle(
        Brush.sweepGradient(
            listOf(clear, shine, clear, clear, clear, soft, clear, clear, clear),
            center,
        ),
        r * .89f,
        style = Stroke(r * .16f),
    )
}
