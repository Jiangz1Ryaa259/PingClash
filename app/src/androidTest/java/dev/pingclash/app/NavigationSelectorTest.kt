// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSelectorTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun rtlMirrorsOnceAndFixedLabelsKeepOneInteractiveRowAtLargeFont() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val selected = mutableStateOf(0)
        var commits = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalLayoutDirection provides direction.value,
                        LocalDensity provides Density(density.density, 2f),
                        LocalMotionAllowed provides false,
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
        assertCenteredOn(0)
        val before =
            (0..3).map {
                compose.onNodeWithTag("nav-glyph-$it", true).fetchSemanticsNode().boundsInRoot
            }
        compose.onNodeWithTag("nav-2").performClick()
        compose.waitForIdle()
        assertCenteredOn(2)
        before.forEachIndexed { index, rect ->
            assertEquals(
                rect,
                compose.onNodeWithTag("nav-glyph-$index", true).fetchSemanticsNode().boundsInRoot,
            )
        }
        compose.runOnIdle {
            direction.value = LayoutDirection.Rtl
            selected.value = 0
        }
        compose.waitForIdle()
        assertCenteredOn(0)
        compose
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(4)
        compose.onNodeWithTag("glass-navigation").performTouchInput {
            down(Offset(width * 7 / 8f, centerY))
            moveBy(Offset(-width * .52f, 0f), 240)
            up()
        }
        compose.waitForIdle()
        assertEquals(2, selected.value)
        assertCenteredOn(2)
        assertEquals(2, commits)
        compose.onNodeWithTag("glass-navigation").performTouchInput {
            down(center)
            moveBy(Offset(width * .40f, 0f), 240)
            cancel()
        }
        compose.waitForIdle()
        assertCenteredOn(2)
        assertEquals(2, commits)
    }

    @Test
    fun pressEnlargesBothAxesAccentFollowsSelectorAndReleaseRestoresPixels() {
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
        val baseline = capture("selector-rest.png")
        try {
            val tab0 = compose.onNodeWithTag("nav-0").fetchSemanticsNode().boundsInRoot
            val tab1 = compose.onNodeWithTag("nav-1").fetchSemanticsNode().boundsInRoot
            assertTrue("Selected tab has no accent pixels", accentPixels(baseline, tab0) > 20)
            assertEquals(
                "Unselected tab received the accent layer",
                0,
                accentPixels(baseline, tab1),
            )
            compose.onNodeWithTag("nav-0").performTouchInput { down(center) }
            compose.waitForIdle()
            val pressImage = capture("selector-pressed.png")
            pressImage.recycle()
            val pressed = compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot
            assertTrue("Press failed to enlarge X", pressed.width > rest.width * 1.05f)
            assertTrue("Press failed to enlarge Y", pressed.height > rest.height * 1.02f)
            compose.onNodeWithTag("nav-0").performTouchInput { cancel() }
            compose.waitForIdle()
            val released = capture("selector-released.png")
            try {
                assertTrue("Press left residual pixels", difference(baseline, released, rest) < 2.0)
            } finally {
                released.recycle()
            }
            compose.onNodeWithTag("nav-1").performClick()
            compose.waitForIdle()
            val moved = capture("selector-moved.png")
            try {
                assertEquals(0, accentPixels(moved, tab0))
                assertTrue(accentPixels(moved, tab1) > 20)
            } finally {
                moved.recycle()
            }
        } finally {
            baseline.recycle()
        }
    }

    private fun assertCenteredOn(index: Int) {
        val selector = compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithTag("nav-$index").fetchSemanticsNode().boundsInRoot
        assertEquals("Selector does not cover the actual tab", tab.center.x, selector.center.x, 2f)
    }

    private fun capture(name: String): Bitmap {
        Thread.sleep(250)
        val file = File(compose.activity.getExternalFilesDir(null), "synthetic-test-evidence/$name")
        file.parentFile?.mkdirs()
        assertTrue(device.takeScreenshot(file))
        return requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
    }

    private fun accentPixels(bitmap: Bitmap, bounds: Rect): Int {
        val accent = navAccentColor(false).toArgb()
        var count = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val pixel = bitmap.getPixel(x, y)
                if (
                    listOf(16, 8, 0).all { shift ->
                        abs((pixel shr shift and 255) - (accent shr shift and 255)) < 12
                    }
                )
                    count++
            }
        }
        return count
    }

    private fun difference(a: Bitmap, b: Bitmap, bounds: Rect): Double {
        var sum = 0.0
        var count = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                for (shift in listOf(16, 8, 0)) {
                    sum += abs((p shr shift and 255) - (q shr shift and 255))
                    count++
                }
            }
        }
        return sum / count
    }
}
