// SPDX-License-Identifier: GPL-3.0-only
// Pixel thresholds, frame times and labels are controlled synthetic test fixtures.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScenicGlassTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // These fixtures replace Activity content directly. Dispose the source before
    // ActivityScenario waits for a quiet main queue during teardown (API 31's
    // Haze compatibility path schedules pre-draw invalidations).
    @After
    fun disposeSyntheticContent() {
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun realBlurRemovesStripeDetailWhileFallbackStaysOpaqueAndToggleReleasesSource() {
        compose.mainClock.autoAdvance = false
        FrameGlassProbe(compose.activity, device) { compose.mainClock.advanceTimeBy(64) }.verify()
    }

    @Test
    fun largeFontLastActionScrollsAboveNavigationAndRemainsClickable() {
        var clicks = 0
        showFrame(mutableStateOf(false), 1.8f) {
            val clearance = LocalNavigationClearance.current
            LazyColumn(
                Modifier.fillMaxSize().testTag("glass-scroll-list"),
                contentPadding = PaddingValues(bottom = clearance + 12.dp),
            ) {
                items(50, key = { it }) { index ->
                    if (index == 49)
                        Button(
                            { clicks++ },
                            Modifier.heightIn(min = 64.dp).testTag("glass-last-action"),
                        ) {
                            Text("Last fixture action")
                        }
                    else Text("Fixture $index", Modifier.padding(20.dp).heightIn(min = 64.dp))
                }
            }
        }
        compose.onNodeWithTag("glass-scroll-list").performScrollToIndex(49)
        val last = compose.onNodeWithTag("glass-last-action").assertIsDisplayed()
        val nav = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Last action is hidden by glass",
            last.fetchSemanticsNode().boundsInRoot.bottom <= nav.top,
        )
        last.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        captureSyntheticFixture(compose, device, "glass-large-font-clearance.png")
    }

    @Test
    fun animatedBackgroundRemainsAvailableAcrossAllTabs() {
        val selected = mutableStateOf(0)
        val scale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                compose.activity.setContent {
                    PingClashTheme("dark") {
                        CompositionLocalProvider(LocalMotionAllowed provides true) {
                            ScenicFrame(
                                selected.value,
                                "ocean",
                                Build.VERSION.SDK_INT == 31,
                                { selected.value = it },
                            ) {
                                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                    DashboardScreen(
                                        DashboardState(
                                            AppData(),
                                            VpnUiState(),
                                            DiagnosticUiState(),
                                            ProxyTestState(),
                                            true,
                                            false,
                                        ),
                                        DashboardActions({}, {}, {}, {}),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(64)
            captureSyntheticFixture(compose, device, "immersive-surface.png")
            for (index in 0..3) {
                compose.runOnUiThread { selected.value = index }
                compose.mainClock.advanceTimeBy(128)
                assertTrue(
                    "Background animation detached from tab $index",
                    compose
                        .onNodeWithTag("ocean-backdrop")
                        .fetchSemanticsNode()
                        .config[DecorativeEffectRunning],
                )
            }
            captureSyntheticFixture(compose, device, "immersive-all-tabs.png")
        } finally {
            compose.mainClock.autoAdvance = true
            val command =
                if (scale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $scale"
            device.executeShellCommand(command)
        }
    }

    @Test
    fun idleWavesActuallyMoveAndReducedMotionFreezesSceneWhileCloudsAndNoneAreSelectable() {
        val scene = mutableStateOf("ocean")
        val reduced = mutableStateOf(false)
        val scale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                compose.activity.setContent {
                    val motion = rememberMotionAllowed(reduced.value)
                    PingClashTheme("light") {
                        CompositionLocalProvider(LocalMotionAllowed provides motion) {
                            ScenicFrame(0, scene.value, Build.VERSION.SDK_INT == 31, {}) {}
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertTrue(
                compose
                    .onNodeWithTag("ocean-backdrop")
                    .fetchSemanticsNode()
                    .config[DecorativeEffectRunning]
            )
            val before = snapshot("ocean-motion-start.png")
            compose.mainClock.advanceTimeBy(4000)
            val moving = snapshot("ocean-motion-end.png")
            assertTrue("Idle scenery is static", pixelDifference(before, moving) > 100)
            before.recycle()
            moving.recycle()
            compose.runOnIdle { reduced.value = true }
            compose.mainClock.advanceTimeBy(64)
            assertFalse(
                compose
                    .onNodeWithTag("ocean-backdrop")
                    .fetchSemanticsNode()
                    .config[DecorativeEffectRunning]
            )
            val paused = snapshot("ocean-motion-paused.png")
            compose.mainClock.advanceTimeBy(4000)
            val still = snapshot("ocean-motion-still.png")
            assertEquals(0, pixelDifference(paused, still))
            paused.recycle()
            still.recycle()
            compose.runOnIdle { scene.value = "clouds" }
            compose.mainClock.advanceTimeBy(64)
            captureSyntheticFixture(compose, device, "clouds-static.png")
            compose.runOnIdle { scene.value = "none" }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("ocean-backdrop").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            val command =
                if (scale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $scale"
            device.executeShellCommand(command)
        }
        assertFalse(VpnRuntime.state.value.active)
    }

    private fun showFrame(
        reduced: State<Boolean>,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                PingClashTheme("light") {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides false,
                        LocalDensity provides Density(density.density, fontScale),
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            ScenicFrame(
                                0,
                                "none",
                                reduced.value || Build.VERSION.SDK_INT == 31,
                                {},
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    private fun snapshot(name: String): Bitmap {
        captureSyntheticFixture(compose, device, name)
        val file = File(compose.activity.getExternalFilesDir(null), name)
        return checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
    }
}

private fun pixelDifference(first: Bitmap, second: Bitmap): Int {
    assertEquals(first.width, second.width)
    assertEquals(first.height, second.height)
    var changed = 0
    for (y in first.height / 3 until first.height * 4 / 5 step 8) for (x in
        0 until first.width step 8) {
        if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
    }
    return changed
}
