// SPDX-License-Identifier: GPL-3.0-only
// Navigation geometry and opacity are deliberate design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

internal val LocalNavigationClearance = staticCompositionLocalOf { 0.dp }
internal val LocalNavHaze = staticCompositionLocalOf<HazeState?> { null }
internal val LocalNavigationBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalSceneDepth = staticCompositionLocalOf { mutableFloatStateOf(0f) }
internal val LocalImmersiveScene = staticCompositionLocalOf { false }
/** Solid scene colour; null keeps the theme background. */
internal val LocalSolidBackground = staticCompositionLocalOf<Color?> { null }

@Composable
internal fun navigationHeight() =
    if (LocalDensity.current.fontScale >= 1.6f) (43 + 36 * LocalDensity.current.fontScale).dp
    else 72.dp

/** Source and glass are siblings. Scrollable content owns its bottom clearance. */
@Composable
internal fun ScenicFrame(
    selected: Int,
    scene: String,
    reduceTransparency: Boolean,
    onSelect: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val haze = remember { HazeState() }
    val window = LocalActivity.current?.window
    val revision = SecurePage.revision
    val secure = remember(window, revision) { SecurePage.isProtected(window) }
    val blur =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !reduceTransparency &&
            !secure &&
            rememberForeground()
    val backdrop = if (blur && Build.VERSION.SDK_INT >= 33) rememberLayerBackdrop() else null
    val legacyBlur = blur && backdrop == null
    val clearance =
        navigationHeight() +
            24.dp +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val depth = remember { mutableFloatStateOf(0f) }
    val cable = remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val three = rememberLanyardThreeState()
    val immersive = scene != "none"
    val parentColors = MaterialTheme.colorScheme
    SideEffect {
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = parentColors.background.luminance() > .5f
                isAppearanceLightNavigationBars = isAppearanceLightStatusBars
            }
        }
    }
    val frame: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalNavigationClearance provides clearance,
            LocalSceneDepth provides depth,
            LocalImmersiveScene provides immersive,
            LocalNavHaze provides if (legacyBlur) haze else null,
            LocalNavigationBackdrop provides backdrop,
            LocalLanyardCable provides cable,
            LocalLanyardThree provides three,
        ) {
            Box(Modifier.fillMaxSize().sceneBackground(scene, LocalSolidBackground.current, Ink)) {
                Box(Modifier.fillMaxSize().navigationSource(backdrop, haze, legacyBlur)) {
                    OceanBackdrop(TrailTone.PENDING, false, Modifier.fillMaxSize(), scene)
                    LanyardSceneLayers(three, cable, selected, secure)
                    Box(Modifier.fillMaxSize().testTag("scenic-content")) { content() }
                }
                GlassNavigation(selected, Modifier.align(Alignment.BottomCenter), onSelect)
            }
        }
    }
    frame()
}

private fun Modifier.navigationSource(backdrop: LayerBackdrop?, haze: HazeState, legacy: Boolean) =
    then(
        when {
            backdrop != null -> Modifier.layerBackdrop(backdrop)
            legacy -> Modifier.hazeSource(haze)
            else -> Modifier
        }
    )

private fun Modifier.sceneBackground(scene: String, solid: Color?, theme: Color) =
    if (scene == "none" && solid != null) background(solidBackdrop(solid)) else background(theme)
