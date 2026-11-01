// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal val LocalMotionAllowed = staticCompositionLocalOf { true }

// Read only the system's motion preference, never network or device identifiers.
// No background worker: observers exist only while this composition is visible.
@Composable
internal fun rememberMotionAllowed(reduceEffects: Boolean = false): Boolean {
    val context = LocalContext.current.applicationContext
    val accessibility =
        remember(context) {
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        }
    fun allowed() =
        runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) > 0f && !accessibility.isTouchExplorationEnabled
            }
            .getOrDefault(false)
    var enabled by remember(context) { mutableStateOf(allowed()) }
    DisposableEffect(context, accessibility) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = allowed()
                }
            }
        val listener =
            AccessibilityManager.TouchExplorationStateChangeListener { enabled = allowed() }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            accessibility.removeTouchExplorationStateChangeListener(listener)
        }
    }
    return enabled && !reduceEffects
}

// Motion dimensions and timings are deliberate visual design constants.
@Suppress("MagicNumber")
@Composable
internal fun VpnPowerControl(phase: String, enabled: Boolean, label: String, onClick: () -> Unit) {
    val motion = LocalMotionAllowed.current
    val foregroundVisible = rememberForeground()
    val connected = phase == "established"
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale =
        animateFloatAsState(
            if (pressed) .97f else 1f,
            if (motion) spring(.85f, 600f) else snap(),
            label = "vpn-press",
        )
    val color by
        animateColorAsState(
            if (connected) MaterialTheme.colorScheme.primaryContainer else Mint,
            if (motion) tween(280) else snap(),
            label = "vpn-color",
        )
    val preferred =
        if (connected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onPrimary
    val foreground = controlForeground(preferred, color)
    val haptic = LocalHapticFeedback.current
    val description = vpnControlDescription(phase)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (phase == "connecting" && motion && foregroundVisible)
            ConnectingRipples(Modifier.matchParentSize())
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            enabled = enabled && phase != "stopping",
            shape = UiCorners.Pill,
            color = color,
            contentColor = foreground,
            border = BorderStroke(1.dp, if (connected) Mint else color),
            interactionSource = interactions,
            modifier =
                Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .testTag("vpn-power")
                    .semantics { stateDescription = description },
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                VpnStateGlyph(phase, motion && foregroundVisible, foreground)
                Text(
                    label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

internal fun vpnControlDescription(phase: String): String =
    tr(
        when (phase) {
            "established" -> R.string.connected
            "connecting",
            "stopping" -> R.string.working
            else -> R.string.disconnected
        }
    )

@Suppress("MagicNumber")
@Composable
private fun ConnectingRipples(modifier: Modifier) {
    val progress =
        rememberInfiniteTransition(label = "connect-ripples")
            .animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(2400, easing = LinearEasing)),
                label = "ripple-progress",
            )
    val color = Mint
    Canvas(modifier) {
        repeat(3) { index ->
            val fraction = (progress.value + index / 3f) % 1f
            val inset = (1f - fraction) * 12.dp.toPx() + 1.dp.toPx()
            drawRoundRect(
                color.copy(alpha = (1f - fraction) * .22f),
                Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset),
                CornerRadius(size.height / 2f),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}
