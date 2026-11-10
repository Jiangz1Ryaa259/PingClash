// SPDX-License-Identifier: GPL-3.0-only
// Only local geometry and fixed UI state enter this renderer. No profile data or credentials.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import org.json.JSONObject

internal val LocalLanyardThree = staticCompositionLocalOf<LanyardThreeState?> { null }
internal val LocalAllowLanyardThree = staticCompositionLocalOf { true }

internal data class ThreeControlText(
    val phase: String,
    val label: String,
    val status: String,
    val motion: Boolean = false,
    val actionRevision: Long = 0L,
    val identity: CardIdentity = CardIdentity(),
)

/** What the card is the ID of: the active profile and routing mode, printed on its faces. */
internal data class CardIdentity(val profile: String = "", val mode: String = "")

internal val LocalCardIdentity = compositionLocalOf { CardIdentity() }

@Stable
internal class LanyardThreeState(private val rendererLost: () -> Unit = {}) {
    var ready by mutableStateOf(false)
        private set

    var displayReady by mutableStateOf(false)
        private set

    var unavailable by mutableStateOf(false)
        private set

    var started by mutableStateOf(false)
        private set

    private var owner: Any? = null
    private var resume = 0L
    private var hadFrame = false
    private val recovery = LanyardRecovery()
    private var epoch = 0L
        set(value) {
            field = value
            ready = false
            lastFrame = ""
            if (hadFrame) recovery.arm()
        }

    var hasControl by mutableStateOf(false)
        private set

    var active = false
        private set

    var dragging by mutableStateOf(false)
        private set

    private var view: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false
    private var scheduled = false
    private var polling = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var slotTop = 0f
    private var slotHeight = 0f
    private var slotCenterX: Float? = null
    private var label = ""
    private var status = ""
    private var identity = CardIdentity()
    private var phase = "idle"
    private var dx = 0f
    private var dy = 0f
    private var held = false
    private var animateFace = false
    private var lastAction = 0L
    private var motion = false
    private var fontScale = 1f
    private var lastFrame = ""
    private val emit = Runnable {
        scheduled = false
        flush()
    }
    private val poll =
        object : Runnable {
            override fun run() {
                val current = view ?: return
                if (!loaded) return
                if (!polling) {
                    polling = true
                    current.evaluateJavascript(
                        "JSON.stringify(window.PingClashScene?.status()||{})"
                    ) { result ->
                        if (view !== current || !loaded) return@evaluateJavascript
                        polling = false
                        val json =
                            runCatching {
                                    JSONObject(org.json.JSONTokener(result).nextValue().toString())
                                }
                                .getOrNull()
                        ready =
                            json?.optBoolean("ready") == true &&
                                json.optLong("epoch", -1) == epoch &&
                                json.optString("paintedPhase") == phase &&
                                json.optLong("resume", -1) == resume
                        if (ready) {
                            unavailable = false
                            displayReady = true
                            schedule()
                            hadFrame = true
                            recovery.clear()
                        }
                    }
                }
                when (recovery.next(active, ready)) {
                    LanyardRecovery.Action.RETRY -> {
                        lastFrame = ""
                        flush()
                    }
                    // Alive but late (a cold WebView or a software GPU can outlast the retries):
                    // show the native card meanwhile and keep offering the latest frame, so a
                    // late first paint still brings the card back. Page errors and renderer
                    // death go through failed() and stay on the native card.
                    LanyardRecovery.Action.FALLBACK -> {
                        unavailable = true
                        displayReady = false
                        recovery.arm()
                    }
                    LanyardRecovery.Action.NONE -> Unit
                }
                if (active && loaded && !ready)
                    handler.postDelayed(this, if (unavailable) LATE_POLL_MS else POLL_MS)
            }
        }
    var renderer: WebView?
        get() = view
        set(value) {
            view = value
            ready = false
            displayReady = false
            hadFrame = false
            loaded = false
            polling = false
            lastFrame = ""
            recovery.clear()
        }

    fun pageLoaded() {
        loaded = true
        recovery.arm()
        lastFrame = ""
        flush()
        handler.post(poll)
    }

    fun failed(dead: WebView? = null) {
        if (dead != null) {
            if (view !== dead) return
            view = null
            dragging = false
            held = false
        }
        handler.removeCallbacks(emit)
        scheduled = false
        ready = false
        displayReady = false
        unavailable = true
        loaded = false
        polling = false
        handler.removeCallbacks(poll)
        if (dead != null) rendererLost()
    }

    fun viewport(width: Int, height: Int) {
        if (viewportWidth != width || viewportHeight != height) {
            displayReady = false
            epoch++
        }
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        schedule()
    }

    fun visible(value: Boolean) {
        if (active == value) return
        active = value
        handler.removeCallbacks(poll)
        if (value) {
            view?.onResume()
            resume++
            ready = false
            polling = false
            if (hadFrame) recovery.arm()
            // The resumed control publishes the latest service phase in this commit.
            // Do not send a cached background phase before that publication.
            lastFrame = ""
            schedule()
            handler.post(poll)
        } else {
            animateFace = false
            flush()
            view?.onPause()
        }
    }

    fun control(token: Any, attached: Boolean) {
        if (attached && owner === token && started) return
        if (!attached && owner !== token) return
        if (attached && owner !== token) {
            // Navigation can reveal the retained WebView before the new home
            // control publishes its phase. Treat that publication as a resume,
            // not as a user-requested flip of the previously painted phase.
            resume++
            ready = false
            animateFace = false
            lastAction = 0L
            if (hadFrame) recovery.arm()
        }
        owner = if (attached) token else null
        held = false
        dragging = false
        dx = 0f
        dy = 0f
        if (attached) started = true else hasControl = false
        schedule()
    }

    fun slot(token: Any, top: Float, height: Float, centerX: Float? = null) {
        // Activity recreation can lay out the control before RESUMED. Cache that
        // geometry while paused; otherwise there may be no later layout callback.
        if (owner !== token) return
        if (!top.isFinite() || !height.isFinite()) return
        hasControl = height > 0
        slotTop = top
        slotHeight = height
        slotCenterX = centerX?.takeIf { it.isFinite() }
        schedule()
    }

    fun pose(
        token: Any,
        value: LanyardPose,
        offset: Offset,
        density: Density,
        text: ThreeControlText,
    ) {
        if (owner !== token) return
        if (text.actionRevision != lastAction) {
            animateFace = active
            lastAction = text.actionRevision
        }
        if (phase != text.phase) {
            epoch++
            if (!active) displayReady = false
        }
        dx = offset.x * density.density
        dy = offset.y * density.density
        held = value.holding
        dragging = held
        motion = text.motion
        phase =
            text.phase.takeIf {
                it in listOf("idle", "connecting", "established", "stopping", "error")
            } ?: "idle"
        label = text.label.take(120)
        status = text.status.take(120)
        identity = CardIdentity(text.identity.profile.take(80), text.identity.mode.take(40))
        fontScale = density.fontScale.coerceIn(1f, 2f)
        schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed(emit, if (held) 16 else 32)
    }

    private fun flush() {
        val current = view ?: return
        if (!loaded) return
        val frame =
            JSONObject()
                .put("active", active && hasControl && slotHeight > 0)
                .put("presented", displayReady)
                .put("motion", motion)
                .put("held", held)
                .put("animateFace", animateFace)
                .put("epoch", epoch)
                .put("resume", resume)
                .put("back", lanyardBackFace(phase))
                .put("phase", phase)
                .put("label", label)
                .put("status", status)
                .put("profile", identity.profile)
                .put("mode", identity.mode)
                .put("viewportWidth", viewportWidth)
                .put("viewportHeight", viewportHeight)
                .put("top", slotTop)
                .put("height", slotHeight)
                .put("fontScale", fontScale)
                .put("centerX", slotCenterX ?: viewportWidth / 2f)
                .put("dx", dx)
                .put("dy", dy)
                .toString()
        if (frame != lastFrame) {
            lastFrame = frame
            current.evaluateJavascript("window.PingClashScene?.frame($frame)", null)
            if (active && !ready) {
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, 80)
            }
        }
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
        view = null
        ready = false
        displayReady = false
        hadFrame = false
        recovery.clear()
        loaded = false
        polling = false
        active = false
        scheduled = false
        owner = null
        hasControl = false
        started = false
        held = false
        dragging = false
        lastFrame = ""
    }
}

private const val POLL_MS = 80L

// Listening for a late renderer stays cheap while the native card is shown.
private const val LATE_POLL_MS = 500L
