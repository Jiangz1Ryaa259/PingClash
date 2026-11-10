// SPDX-License-Identifier: GPL-3.0-only
// Motion constants were tuned with the owner on an interactive prototype.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val HomeModes =
    listOf(
        "rule" to R.string.rules,
        "global" to R.string.ui_mode_global_short,
        "direct" to R.string.direct,
    )

/** Degrees between neighbouring modes; the three modes repeat all the way round the drum. */
internal const val DRUM_SPACING = 14f
// Six full cycles of the three modes; knurl (.9°) and ticks (1.75°) repeat within it too, so a
// resting drum can be wrapped by this much without a visible jump.
private const val DRUM_SPAN = DRUM_SPACING * 18
private const val COAST_TAU = .45f
private const val SLOW_RELEASE = 80f
private const val REBOUND = DRUM_SPACING * .15f
private val SETTLE_W = (2 * PI / .35).toFloat()
private const val SETTLE_Z = .8f
private val REBOUND_W = (2 * PI / .3).toFloat()
private const val REBOUND_Z = .55f
private const val REDUCED_MILLIS = 180f

/** The mode shown at a notch: notch k is mode k mod 3. */
internal fun drumMode(notch: Int) = ((notch % 3) + 3) % 3

private fun springPeak(w: Float, z: Float): Float {
    val wd = w * sqrt(1 - z * z)
    val t = atan(wd / (z * w)) / wd
    return exp(-z * w * t) * sin(wd * t) / wd
}

/** The damping that makes a spring released [distance] from rest pass it by [REBOUND]. */
private fun reboundDamping(distance: Float): Float {
    if (distance <= 0f) return 1f
    val l = ln((REBOUND / distance).coerceAtMost(.9f))
    return (-l / sqrt((PI * PI).toFloat() + l * l)).coerceIn(.2f, 1f)
}

/**
 * The drum's motion: the finger turns it directly; a flick coasts on friction, aimed so it reaches
 * a mode still moving and passes it by a few degrees, and a spring brings it back. Slow releases
 * and taps use the spring alone, with the same rebound. The mode is reported once, at the moment
 * the drum first reaches it ([onArrive]); passing other modes on the way only ticks ([onTick]).
 */
// One state machine: the gestures, the three motions and the state they share belong together.
@Suppress("TooManyFunctions")
@Stable
internal class ModeDrumMotion(initial: Int, private val scope: CoroutineScope) {
    var rotation by mutableFloatStateOf(-initial * DRUM_SPACING)
        private set

    var dragging by mutableStateOf(false)
        private set

    var moving by mutableStateOf(false)
        private set

    /** The notch the drum is heading for, or resting on. */
    var goal by mutableIntStateOf(initial)
        private set

    /** False while a moving drum has not yet reached [goal]. */
    var arrived by mutableStateOf(true)
        private set

    /** Bumped whenever the drum comes to rest. */
    var rests by mutableIntStateOf(0)
        private set

    /** Reconciliations with a mode changed elsewhere, for tests. */
    var settlements by mutableIntStateOf(0)
        private set

    val pulse = Animatable(0f)
    var onTick: () -> Unit = {}
    var onArrive: (notch: Int) -> Unit = {}

    private var velocity = 0f
    private var job: Job? = null
    private var lastNotch = initial
    private var commit = true

    val notch: Int
        get() = (-rotation / DRUM_SPACING).roundToInt()

    /** The notch the controls act on: where the drum is going, or where it is. */
    val logical: Int
        get() = if (moving && !dragging) goal else notch

    fun beginDrag() {
        job?.cancel()
        moving = false
        dragging = true
        arrived = false
        velocity = 0f
        lastNotch = notch
    }

    fun dragBy(degrees: Float) {
        rotation += degrees
        tick()
    }

    /** The finger lifted at [speed] degrees per second. */
    fun release(speed: Float, motion: Boolean) {
        dragging = false
        commit = true
        velocity = if (motion) speed else 0f
        val near = nearest(rotation)
        val target = nearest(rotation + speed * COAST_TAU)
        val to = -target * DRUM_SPACING
        val distance = to - rotation
        val arrival = sign(distance) * REBOUND / springPeak(REBOUND_W, REBOUND_Z)
        val slow = abs(speed) < SLOW_RELEASE
        val coasts = !slow && distance * speed > 0f && abs(speed) > 1.5f * abs(arrival)
        when {
            !motion -> glide(if (slow) near else near - sign(speed).toInt())
            slow -> spring(nearest(rotation + speed * .08f))
            !coasts -> spring(target)
            // Aim the coast past the mode so it crosses it at the arrival speed.
            else -> {
                val stop = (to * speed - arrival * rotation) / (speed - arrival)
                coast(target, ((stop - rotation) / speed).coerceIn(.05f, .9f))
            }
        }
    }

    /** The gesture ended without a release (cancelled, or the drum was disabled). */
    fun cancelDrag(motion: Boolean) {
        if (!dragging) return
        dragging = false
        commit = false
        velocity = 0f
        if (motion) spring(nearest(rotation)) else glide(nearest(rotation))
    }

    /** A tap on one side: one mode that way, from wherever the drum is heading. */
    fun step(direction: Int, motion: Boolean) {
        val target = logical + direction
        commit = true
        if (motion) spring(target) else glide(target)
    }

    /** Shows [mode] without reporting it: the mode changed elsewhere or a change was refused. */
    fun settleTo(mode: Int, motion: Boolean) {
        val base = notch
        val target = (-1..1).map { base + it }.first { drumMode(it) == mode }
        settlements++
        commit = false
        if (motion) spring(target)
        else {
            rotation = -target * DRUM_SPACING
            goal = target
            rest()
        }
    }

    fun emphasize() {
        scope.launch {
            pulse.snapTo(.6f)
            pulse.animateTo(0f, tween(260))
        }
    }

    private fun nearest(angle: Float) = (-angle / DRUM_SPACING).roundToInt()

    private fun tick() {
        val now = notch
        if (now == lastNotch) return
        lastNotch = now
        if (!arrived) onTick()
    }

    private fun begin(target: Int, body: suspend () -> Unit) {
        job?.cancel()
        goal = target
        val to = -target * DRUM_SPACING
        arrived = abs(rotation - to) < .01f
        moving = true
        lastNotch = notch
        job = scope.launch { body() }
    }

    private fun arrive() {
        if (arrived) return
        arrived = true
        lastNotch = notch
        scope.launch {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(320))
        }
        if (commit) onArrive(goal)
    }

    private fun coast(target: Int, tau: Float) =
        begin(target) {
            val to = -target * DRUM_SPACING
            val side = sign(rotation - to)
            var last = withFrameNanos { it }
            while (!arrived) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1e9f).coerceAtMost(1f / 30)
                last = now
                velocity *= exp(-dt / tau)
                rotation += velocity * dt
                tick()
                if (sign(rotation - to) != side) arrive()
                // Spent before reaching the mode (a clamped coast): the spring finishes it.
                else if (abs(velocity) < 2f) break
            }
            settle(to, REBOUND_W, REBOUND_Z)
        }

    private fun spring(target: Int) {
        val distance = abs(-target * DRUM_SPACING - rotation)
        val damping = if (distance > .3f) reboundDamping(distance) else REBOUND_Z
        val stiffness = if (distance > .3f) REBOUND_W else SETTLE_W
        begin(target) {
            settle(-target * DRUM_SPACING, stiffness, if (distance > .3f) damping else SETTLE_Z)
        }
    }

    private suspend fun settle(to: Float, w: Float, z: Float) {
        val side = sign(rotation - to)
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(1f / 30)
            last = now
            val h = dt / 8
            repeat(8) {
                val x = rotation - to
                velocity += (-w * w * x - 2 * z * w * velocity) * h
                rotation += velocity * h
            }
            tick()
            if (sign(rotation - to) != side) arrive()
            if (abs(rotation - to) < .02f && abs(velocity) < .5f) break
        }
        rotation = to
        arrive()
        rest()
    }

    private fun glide(target: Int) =
        begin(target) {
            val from = rotation
            val to = -target * DRUM_SPACING
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val u = ((now - start) / 1e6f / REDUCED_MILLIS).coerceAtMost(1f)
                rotation = from + (to - from) * u * u * (3 - 2 * u)
                if (u >= 1f) break
            }
            arrive()
            rest()
        }

    private fun rest() {
        velocity = 0f
        val wrapped = ((rotation % DRUM_SPAN) + DRUM_SPAN) % DRUM_SPAN
        rotation = if (wrapped > DRUM_SPAN / 2) wrapped - DRUM_SPAN else wrapped
        goal = notch
        lastNotch = goal
        arrived = true
        moving = false
        rests++
    }
}

internal fun modeFeedback(view: android.view.View) {
    view.performHapticFeedback(
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.view.HapticFeedbackConstants.CONFIRM
        else android.view.HapticFeedbackConstants.VIRTUAL_KEY
    )
}
