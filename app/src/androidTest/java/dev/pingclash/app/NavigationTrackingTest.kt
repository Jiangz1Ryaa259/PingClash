// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTrackingTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun continuousDragFollowsEachFrameThenCancelReturnsWithoutCommitting() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val selected = mutableStateOf(0)
        var commits = 0
        var dragging = false
        val trace = JSONArray()
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides true,
                        LocalLayoutDirection provides direction.value,
                    ) {
                        ScenicFrame(
                            selected.value,
                            "none",
                            false,
                            {
                                selected.value = it
                                commits++
                            },
                        ) {
                            Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF79D6CE)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        try {
            for (rtl in listOf(false, true)) {
                compose.runOnIdle {
                    direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val bar =
                    compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot
                val step = bar.width / 4 * .14f * (if (rtl) -1 else 1)
                compose.onNodeWithTag("glass-navigation").performTouchInput {
                    down(Offset(if (rtl) width * 7 / 8f else width / 8f, centerY))
                    moveBy(Offset(step * 2, 0f), 16)
                }
                dragging = true
                // Calibrate after touch-slop and the initial press transition.
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                val start = selectorX()
                repeat(6) { frame ->
                    compose.onNodeWithTag("glass-navigation").performTouchInput {
                        moveBy(Offset(step, 0f), 16)
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val expected = start + step * (frame + 1)
                    val actual = selectorX()
                    trace.put(
                        JSONObject()
                            .put("rtl", rtl)
                            .put("frame", frame)
                            .put("expectedX", expected)
                            .put("actualX", actual)
                            .put("errorPx", abs(actual - expected))
                    )
                }
                val evidence =
                    File(
                        compose.activity.getExternalFilesDir(null),
                        "synthetic-test-evidence/navigation-tracking.json",
                    )
                evidence.parentFile?.mkdirs()
                evidence.writeText(trace.toString())
                val worst =
                    (0 until trace.length()).maxOf { trace.getJSONObject(it).getDouble("errorPx") }
                assertTrue("Finger tracking lags behind the next frame: $worst px", worst <= 3.0)
                compose.onNodeWithTag("glass-navigation").performTouchInput { cancel() }
                dragging = false
                compose.mainClock.advanceTimeBy(1200)
                compose.waitForIdle()
                val tab = compose.onNodeWithTag("nav-0").fetchSemanticsNode().boundsInRoot
                assertEquals(tab.center.x, selectorX(), 2f)
                assertEquals(0, commits)
                compose.mainClock.autoAdvance = true
            }
        } finally {
            if (dragging) compose.onNodeWithTag("glass-navigation").performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun releasedTapKeepsLensExpandedDuringTravelThenReturnsToRest() {
        val selected = mutableStateOf(0)
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(LocalMotionAllowed provides true) {
                        ScenicFrame(selected.value, "none", false, { selected.value = it }) {
                            Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF79D6CE)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val rest = compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            // Semantics click has no held pointer: tap travel must activate optics itself.
            compose.onNodeWithTag("nav-3").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val moving = compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot
            val target = compose.onNodeWithTag("nav-3").fetchSemanticsNode().boundsInRoot
            assertTrue("Selector did not travel", moving.center.x > rest.center.x + 10)
            assertTrue(
                "Lens collapsed before the tap transition ended",
                moving.height > rest.height * 1.01f,
            )
            compose.mainClock.advanceTimeBy(1600)
            compose.waitForIdle()
            val settled = compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot
            assertEquals(target.center.x, settled.center.x, 2f)
            assertEquals(rest.width, settled.width, 2f)
            assertEquals(rest.height, settled.height, 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun selectorX() =
        compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot.center.x
}
