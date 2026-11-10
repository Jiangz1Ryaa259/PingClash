// SPDX-License-Identifier: GPL-3.0-only
// Physical proportions, lighting and spring values are deliberate visual design constants.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LanyardDisplacement = SemanticsPropertyKey<Float>("LanyardDisplacement")
internal val LanyardAnimating = SemanticsPropertyKey<Boolean>("LanyardAnimating")
internal val LanyardFaceAngle = SemanticsPropertyKey<Float>("LanyardFaceAngle")

@Stable
internal class LanyardPose {
    var drag by mutableStateOf(Offset.Zero)
    var holding by mutableStateOf(false)
}

private class LanyardMeasurement {
    var top = Float.NaN
    var height = Float.NaN
    var center = Float.NaN
}

/** Native hanging control. Dragging only changes its pose; VPN actions come from clicks. */
@Composable
internal fun LanyardPowerControl(
    phase: String,
    enabled: Boolean,
    label: String,
    stageHeight: Dp? = null,
    onClick: () -> Unit,
) {
    val moving = LocalMotionAllowed.current && rememberForeground()
    val three = LocalLanyardThree.current
    // A tap always counts, even while the 3D card restarts (just after returning to Home): the
    // VPN does not depend on the renderer. Only dragging waits, since the card must follow it.
    val available = enabled && phase != "stopping"
    val draggable = moving && three.acceptsInput()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val action by rememberUpdatedState(onClick)
    val pose = remember { LanyardPose() }
    val follow = moving && pose.holding
    val spec = if (moving && !pose.holding) spring<Float>(.64f, 190f) else snap()
    val x by animateFloatAsState(if (follow) pose.drag.x else 0f, spec, label = "lanyard-x")
    val y by animateFloatAsState(if (follow) pose.drag.y else 0f, spec, label = "lanyard-y")
    val turn by
        animateFloatAsState(
            if (lanyardBackFace(phase)) 180f else 0f,
            if (moving) spring(.83f, 180f) else snap(),
            label = "lanyard-face",
        )
    val large = density.fontScale >= 1.6f
    val metrics = lanyardMetrics(large, stageHeight)
    val height = metrics.height
    val cardHeight = metrics.card
    val top = metrics.top
    val token = remember { Any() }
    val measurement = remember { LanyardMeasurement() }
    var actionRevision by remember { mutableLongStateOf(0L) }
    DisposableEffect(three) { onDispose { three?.control(token, false) } }
    val description = vpnControlDescription(phase)
    val anchor = Modifier.lanyardAnchor()
    val performTap = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        actionRevision++
        action()
    }
    val identity = LocalCardIdentity.current
    val cardText =
        ThreeControlText(phase, label, lanyardPhaseText(phase), moving, actionRevision, identity)
    SideEffect {
        three?.control(token, true)
        three?.slot(token, measurement.top, measurement.height, measurement.center)
        // The JS controller owns release motion. A held pointer must bypass
        // Compose's animation frame scheduling and arrive without a second spring.
        three?.pose(token, pose, pose.heldOffset(), density, cardText)
    }
    LaunchedEffect(available, moving) {
        pose.drag = Offset.Zero
        pose.holding = false
    }
    Box(
        Modifier.fillMaxWidth()
            .height(height)
            .onGloballyPositioned {
                measurement.top = it.positionInRoot().y + (top.value + 26) * density.density
                measurement.height = (cardHeight.value - 26) * density.density
                measurement.center = it.positionInRoot().x + it.size.width / 2f
                three?.slot(token, measurement.top, measurement.height, measurement.center)
            }
            .testTag("lanyard-stage")
            .semantics {
                this[LanyardDisplacement] = kotlin.math.abs(x) + kotlin.math.abs(y)
                this[LanyardFaceAngle] = turn
                this[LanyardAnimating] =
                    lanyardInMotion(moving, pose.holding, kotlin.math.abs(x) + kotlin.math.abs(y))
            }
    ) {
        if (three == null) LocalLanyardStrap(x, y, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopCenter)
                .padding(top = top)
                .width((cardHeight - 26.dp) * (1.6f / 2.25f))
                .height(cardHeight)
                // Input lives outside the visual transform. A mirrored/moving
                // hit layer must never feed its local deltas back into the pose.
                .testTag("vpn-power")
                .lanyardAction(available, label, description, performTap)
                .lanyardGestures(available, draggable, density.density, pose, performTap)
        ) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        translationX = x * density.density
                        translationY = y * density.density
                        rotationZ = (x * .19f).coerceIn(-25f, 25f)
                        rotationX = (-y * .38f).coerceIn(-20f, 20f)
                        rotationY =
                            if (three?.displayReady == true) turn + (x * .30f).coerceIn(-20f, 20f)
                            else 0f
                        transformOrigin = TransformOrigin(.5f, .04f)
                        cameraDistance = 1100f * density.density
                        alpha = cardOpacity(available)
                    }
                    .then(anchor)
            ) {
                if (three.nativeFallback()) {
                    MinimalLanyardFallback(phase, label, Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun lanyardInMotion(moving: Boolean, holding: Boolean, distance: Float) =
    moving && (holding || distance > .1f)

private fun cardOpacity(available: Boolean) = if (available) 1f else .62f

internal fun lanyardBackFace(phase: String) = phase in listOf("connecting", "established")

internal fun lanyardPhaseText(phase: String) =
    tr(
        when (phase) {
            "established" -> R.string.vpn_tunnel_established
            "connecting" -> R.string.connecting
            "stopping" -> R.string.disconnecting
            "error" -> R.string.ui_connection_failed
            else -> R.string.not_connected
        }
    )

/** Native fallback only when WebGL is unavailable. Preparation shows the scenery. */
@Composable
private fun MinimalLanyardFallback(phase: String, label: String, modifier: Modifier) {
    Box(modifier.testTag("lanyard-minimal-fallback"), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Surface(
            shape = UiCorners.Card,
            color = Panel.copy(alpha = .96f),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppGlyph(
                    if (phase == "established") PcIcon.Check else PcIcon.Power,
                    Modifier.size(30.dp),
                    Mint,
                )
                Text(
                    lanyardPhaseText(phase),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun LanyardThreeState?.nativeFallback() = this == null || unavailable

private fun LanyardPose.heldOffset() = if (holding) drag else Offset.Zero

private fun LanyardThreeState?.acceptsInput() =
    this == null || unavailable || !displayReady || ready
