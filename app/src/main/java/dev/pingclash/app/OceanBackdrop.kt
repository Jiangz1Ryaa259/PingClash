// SPDX-License-Identifier: GPL-3.0-only
// Palette coordinates and wave geometry are visual design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val DecorativeEffectRunning = SemanticsPropertyKey<Boolean>("DecorativeEffectRunning")
internal val SceneDiveProgress = SemanticsPropertyKey<Float>("SceneDiveProgress")
internal const val CONNECTION_TINT_ALPHA = .94f
internal const val NAV_SELECTOR_EDGE_ALPHA = .80f

@Composable
internal fun rememberForeground(): Boolean =
    LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value ==
        Lifecycle.State.RESUMED

/** Local photographic layers with bounded mesh refraction and scroll-linked immersion. */
@Composable
internal fun OceanBackdrop(
    tone: TrailTone,
    connecting: Boolean,
    modifier: Modifier = Modifier,
    scene: String = "ocean",
) {
    if (scene == "none" || scene == "image") return
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        RealtimeOcean(modifier, scene == "clouds")
        return
    }
    val motion = LocalMotionAllowed.current
    val animate = motion && rememberForeground()
    val phase: State<Float> =
        if (animate) {
            rememberInfiniteTransition(label = "ocean-drift")
                .animateFloat(
                    0f,
                    1f,
                    infiniteRepeatable(
                        tween(if (connecting) 36000 else 60000, easing = LinearEasing)
                    ),
                    label = "ocean-phase",
                )
        } else remember { mutableFloatStateOf(0f) }
    val resources = LocalContext.current.resources
    val surface =
        remember(resources, scene) {
            PhotographicScene(
                android.graphics.BitmapFactory.decodeResource(
                    resources,
                    if (scene == "clouds") R.drawable.clouds_v3 else R.drawable.ocean_surface_v3,
                ),
                scene == "clouds",
            )
        }
    val underwater =
        remember(resources, scene) {
            if (scene == "clouds") null
            else
                PhotographicScene(
                    android.graphics.BitmapFactory.decodeResource(
                        resources,
                        R.drawable.ocean_depth_v3,
                    )
                )
        }
    val depth = LocalSceneDepth.current
    Canvas(
        modifier.testTag("ocean-backdrop").semantics {
            this[DecorativeEffectRunning] = animate
            this[SceneDiveProgress] = depth.floatValue
        }
    ) {
        val dive = if (motion) depth.floatValue.coerceIn(0f, 1f) else 0f
        val blend = (dive * dive * (3 - 2 * dive)).coerceIn(0f, 1f)
        val frame = SceneFrame(size.width, size.height, phase.value, dive)
        drawIntoCanvas { canvas ->
            surface.draw(canvas.nativeCanvas, frame)
            underwater?.draw(canvas.nativeCanvas, frame, blend)
        }
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xA6000A12), Color(0x0A00101C), Color(0x80000712)),
                startY = 0f,
                endY = size.height,
            )
        )
        if (tone == TrailTone.FAILED) drawRect(Color.Black.copy(alpha = .06f))
    }
}

internal fun oceanWaveColors(dark: Boolean, tone: TrailTone): List<Color> =
    when (tone) {
        TrailTone.FAILED,
        TrailTone.INCOMPLETE ->
            if (dark) listOf(0xFF234C65, 0xFF173D57, 0xFF0E3048)
            else listOf(0xFF76C1DE, 0xFF358FB6, 0xFF0F648F)
        else ->
            if (dark) listOf(0xFF234E6C, 0xFF16425F, 0xFF0A2F4C)
            else listOf(0xFF5FB9DA, 0xFF2189B4, 0xFF08618C)
    }.map(::Color)
