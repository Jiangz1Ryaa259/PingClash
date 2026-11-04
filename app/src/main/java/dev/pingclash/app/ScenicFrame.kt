// SPDX-License-Identifier: GPL-3.0-only
// Navigation geometry and opacity are deliberate design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

internal val LocalNavigationClearance = staticCompositionLocalOf { 0.dp }
/** True when the navigation is a rail on the start edge (landscape) instead of a bottom bar. */
internal val LocalNavigationRail = staticCompositionLocalOf { false }
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
    val (rail, clearance) = navigationLayout()
    val sidebar = rememberSidebarWidth()
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
    CompositionLocalProvider(
        LocalNavigationClearance provides clearance,
        LocalNavigationRail provides rail,
        LocalSceneDepth provides depth,
        LocalImmersiveScene provides immersive,
        LocalNavHaze provides if (legacyBlur) haze else null,
        LocalNavigationBackdrop provides backdrop,
        LocalLanyardCable provides cable,
        LocalLanyardThree provides three,
    ) {
        Box(Modifier.fillMaxSize().sceneBackground(scene, LocalSolidBackground.current, Ink)) {
            Box(Modifier.fillMaxSize().navigationSource(backdrop, haze, legacyBlur)) {
                if (scene == "image") CustomImageBackdrop(Modifier.fillMaxSize())
                else OceanBackdrop(TrailTone.PENDING, false, Modifier.fillMaxSize(), scene)
                // The badge layer places itself in window coordinates from the page's anchor, so
                // it stays full-bleed with the scene; only the pages make room for the sidebar.
                LanyardSceneLayers(three, cable, selected, secure)
                // Insets are applied in layout and the animated width is read here, so neither
                // recomposes the frame itself (its SideEffect sets the system bars).
                Box(
                    Modifier.fillMaxSize()
                        .then(
                            if (rail)
                                Modifier.windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
                                    )
                                    .padding(start = sidebar.width.value)
                            else Modifier
                        )
                        .testTag("scenic-content")
                ) {
                    content()
                }
            }
            if (rail)
                GlassSidebar(
                    selected,
                    sidebar.width.value,
                    sidebar.toggle,
                    Modifier.align(Alignment.CenterStart),
                    onSelect,
                )
            else GlassNavigation(selected, Modifier.align(Alignment.BottomCenter), onSelect)
        }
    }
}

private const val RAIL_MIN_WIDTH = 600

/** Sidebar or bar, and the pages' bottom clearance. */
private data class NavigationLayout(val rail: Boolean, val bottom: Dp)

@Composable
private fun navigationLayout(): NavigationLayout {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val configuration = LocalConfiguration.current
    // Landscape with room for a sidebar: phones held sideways and tablets. The bar would take
    // a quarter of a landscape phone's height.
    val rail =
        configuration.screenWidthDp > configuration.screenHeightDp &&
            configuration.screenWidthDp >= RAIL_MIN_WIDTH
    return if (rail) NavigationLayout(true, bottomInset + 12.dp)
    else NavigationLayout(false, navigationHeight() + 24.dp + bottomInset)
}

private class SidebarWidth(val width: State<Dp>, val toggle: () -> Unit)

/**
 * The sidebar's animated width. Its collapsed or expanded state is remembered; before the first
 * choice it opens on tablets and stays collapsed on phones held sideways.
 */
@Composable
private fun rememberSidebarWidth(): SidebarWidth {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Tablets only: a wide phone held sideways is still short, and the rows need its width.
    val wide =
        configuration.screenWidthDp >= SIDEBAR_OPEN_WIDTH &&
            configuration.screenHeightDp >= SIDEBAR_OPEN_HEIGHT
    val prefs = remember(context) { context.getSharedPreferences(UI_PREFS, 0) }
    var collapsed by rememberSaveable {
        mutableStateOf(prefs.getBoolean(SIDEBAR_COLLAPSED_KEY, !wide))
    }
    val motion = LocalMotionAllowed.current
    val width =
        animateDpAsState(
            if (collapsed) SIDEBAR_COLLAPSED else SIDEBAR_EXPANDED,
            if (motion) spring(dampingRatio = .86f, stiffness = 420f) else snap(),
            label = "sidebar-width",
        )
    return SidebarWidth(width) {
        collapsed = !collapsed
        prefs.edit().putBoolean(SIDEBAR_COLLAPSED_KEY, collapsed).apply()
    }
}

private const val UI_PREFS = "ui"
private const val SIDEBAR_COLLAPSED_KEY = "sidebar_collapsed"
private const val SIDEBAR_OPEN_WIDTH = 840
private const val SIDEBAR_OPEN_HEIGHT = 600

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
