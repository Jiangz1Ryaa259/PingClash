// SPDX-License-Identifier: GPL-3.0-only
// Optical causal test uses static synthetic pixels, never a user page or network.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidOpticsTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun actualLensWarpsOnlyTheRimAndLegacyDevicesSelectTheirFallback() {
        val material = mutableStateOf(navigationGlassStyle(false))
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides false,
                        LocalNavigationGlassStyle provides material.value,
                    ) {
                        ScenicFrame(0, "none", false, {}) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color(0xFF263759))
                                val w = 28.dp.toPx()
                                var y = 0f
                                var row = 0
                                val colors =
                                    listOf(Color(0xFFEE676F), Color(0xFF79D6CE), Color(0xFFEBC975))
                                // Flat top/bottom edges refract vertically. A vertical-only
                                // stripe pattern cannot reveal that displacement.
                                while (y < size.height) {
                                    var x = 0f
                                    var column = 0
                                    while (x < size.width) {
                                        drawRect(
                                            colors[(row + column) % colors.size],
                                            Offset(x, y),
                                            Size(w, w),
                                        )
                                        x += w
                                        column++
                                    }
                                    y += w
                                    row++
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val nav = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode()
        val backend =
            if (Build.VERSION.SDK_INT >= 33) "backdrop"
            else if (Build.VERSION.SDK_INT >= 31) "haze" else "opaque"
        assertEquals(backend, nav.config[NavOpticalBackend])
        if (Build.VERSION.SDK_INT < 33) return
        // PixelCopy captures window pixels, not the Compose root's local origin.
        val bounds = nav.boundsInWindow
        val refracted = capture("liquid-rim-enabled.png")
        compose.runOnIdle { material.value = material.value.copy(refractionAmount = 0.dp) }
        compose.waitForIdle()
        val plain = capture("liquid-rim-disabled.png")
        try {
            val rim = pixelDifference(refracted, plain, bounds, true)
            val middle = pixelDifference(refracted, plain, bounds, false)
            assertTrue("Lens did not displace real source pixels at the rim: $rim", rim > 2.0)
            assertTrue("Lens moved the flat center instead of only the rim: $middle", middle < 2.0)
            recordMeasurement(rim, middle)
        } finally {
            refracted.recycle()
            plain.recycle()
        }
    }

    @Test
    fun physicalTabPressLeavesNoDarkStateLayerAfterRelease() {
        val selected = mutableStateOf(1)
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    ScenicFrame(selected.value, "none", false, { selected.value = it }) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color(0xFF79D6CE))
                            drawRect(
                                Color(0xFFEBC975),
                                Offset(size.width / 2, 0f),
                                Size(size.width / 2, size.height),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val baseline = capture("navigation-before-press.png")
        compose.onNodeWithTag("nav-2").performTouchInput { click() }
        compose.onNodeWithTag("nav-1").performTouchInput { click() }
        compose.waitForIdle()
        Thread.sleep(1200)
        val released = capture("navigation-after-release.png")
        val bounds = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInWindow
        try {
            val difference = pixelDifference(baseline, released, bounds, false)
            assertTrue(
                "A pressed state layer remained after release: $difference",
                difference < 2.0,
            )
            assertEquals(1, selected.value)
        } finally {
            baseline.recycle()
            released.recycle()
        }
    }

    private fun recordMeasurement(rim: Double, middle: Double) {
        val file =
            File(
                compose.activity.getExternalFilesDir(null),
                "synthetic-test-evidence/liquid-optics.json",
            )
        file.writeText(
            org.json
                .JSONObject()
                .put("api", Build.VERSION.SDK_INT)
                .put("backend", "backdrop")
                .put("rimMeanRgbDifference", rim)
                .put("flatMeanRgbDifference", middle)
                .put("publicNetworkUsed", false)
                .toString()
        )
    }

    private fun capture(name: String): Bitmap {
        val file = File(compose.activity.getExternalFilesDir(null), "synthetic-test-evidence/$name")
        file.parentFile?.mkdirs()
        if (Build.VERSION.SDK_INT >= 33) {
            // The backdrop lens needs the window's own pixels after a fresh frame.
            compose.runOnUiThread { compose.activity.window.decorView.invalidate() }
            compose.waitForIdle()
            Thread.sleep(250)
            captureSyntheticWindow(compose.activity, file)
        } else {
            // The legacy blur backend animates continuously, so waiting for idle never
            // returns here; the settled screen is captured with the shell screenshot.
            Thread.sleep(250)
            check(
                androidx.test.uiautomator.UiDevice.getInstance(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    )
                    .takeScreenshot(file)
            ) {
                "Synthetic screenshot was not saved"
            }
        }
        return requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
    }

    private fun pixelDifference(a: Bitmap, b: Bitmap, bounds: Rect, rim: Boolean): Double {
        val left = bounds.left
        val top = bounds.top
        val width = bounds.width
        val height = bounds.height
        val xs = if (rim) .32f to .90f else .46f to .48f
        val ys = if (rim) .04f to .12f else .45f to .55f
        var sum = 0.0
        var samples = 0
        for (y in (top + height * ys.first).toInt()..(top + height * ys.second).toInt()) {
            for (x in (left + width * xs.first).toInt()..(left + width * xs.second).toInt()) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                sum +=
                    kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(q))
                sum +=
                    kotlin.math.abs(
                        android.graphics.Color.green(p) - android.graphics.Color.green(q)
                    )
                sum +=
                    kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q))
                samples += 3
            }
        }
        return sum / samples
    }
}
