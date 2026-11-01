// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Real frames without Espresso's idle prerequisite: API 31 Haze redraws on pre-draw. */
internal class FrameGlassProbe(
    private val activity: MainActivity,
    private val device: UiDevice,
    private val advanceFrame: () -> Unit,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reduced = mutableStateOf(false)
    private val sampling = AtomicReference<Sampling?>()
    private val enabled = AtomicReference<Boolean?>()
    private val directory =
        File(activity.getExternalFilesDir(null), "synthetic-test-evidence").apply { mkdirs() }

    private data class Sampling(val width: Float, val top: Float, val density: Float)

    fun verify() {
        val expected = Build.VERSION.SDK_INT >= 31
        val owner = Any()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PingClashTheme("light") {
                        CompositionLocalProvider(LocalMotionAllowed provides false) {
                            ScenicFrame(0, "none", reduced.value, {}) {
                                val density = LocalDensity.current
                                val inset = WindowInsets.navigationBars.getBottom(density)
                                val height = with(density) { navigationHeight().toPx() }
                                val blur =
                                    LocalNavHaze.current != null ||
                                        LocalNavigationBackdrop.current != null
                                SideEffect { enabled.set(blur) }
                                Canvas(
                                    Modifier.fillMaxSize().onGloballyPositioned {
                                        val bounds = it.boundsInWindow()
                                        sampling.set(
                                            Sampling(
                                                bounds.width,
                                                bounds.bottom -
                                                    inset -
                                                    with(density) { 12.dp.toPx() } -
                                                    height,
                                                density.density,
                                            )
                                        )
                                    }
                                ) {
                                    drawRect(Color.White)
                                    val stripe = 8.dp.toPx()
                                    var x = 0f
                                    while (x < size.width) {
                                        drawRect(
                                            Color.Black,
                                            Offset(x, 0f),
                                            Size(stripe, size.height),
                                        )
                                        x += stripe * 2
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await("source placement") { sampling.get() != null && enabled.get() == expected }
            val sampled = sample("glass-sampling.png", expected)
            changeReduced(true)
            val fallback = sample("glass-reduced-transparency.png", false)
            repeat(3) {
                changeReduced(false)
                changeReduced(true)
            }
            instrumentation.runOnMainSync {
                reduced.value = false
                SecurePage.acquire(activity.window, owner)
            }
            await("protected source detached") { enabled.get() == false }
            instrumentation.runOnMainSync { SecurePage.release(activity.window, owner) }
            await("source restored after replacement frames") { enabled.get() == expected }
            File(directory, "glass-sampling.json")
                .writeText(
                    JSONObject()
                        .put("api", Build.VERSION.SDK_INT)
                        .put("blurEnabled", expected)
                        .put("mean", sampled.first)
                        .put("standardDeviation", sampled.second)
                        .put("fallbackMean", fallback.first)
                        .put("frameSamplingWithoutEspresso", true)
                        .toString()
                )
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent {}
                SecurePage.release(activity.window, owner)
            }
            advanceFrame()
        }
    }

    private fun changeReduced(value: Boolean) {
        instrumentation.runOnMainSync { reduced.value = value }
        await("transparency preference") {
            enabled.get() == (!value && Build.VERSION.SDK_INT >= 31)
        }
    }

    private fun sample(name: String, blur: Boolean): Pair<Double, Double> {
        var result = 0.0 to 255.0
        await("rendered $name: $blur") {
            val bounds = checkNotNull(sampling.get())
            val file = File(directory, name)
            assertTrue(device.takeScreenshot(file))
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                val y = (bounds.top + 8 * bounds.density).toInt()
                val values =
                    ((bounds.width * .35f).toInt()..(bounds.width * .65f).toInt()).map { x ->
                        val pixel = bitmap.getPixel(x, y)
                        (android.graphics.Color.red(pixel) +
                            android.graphics.Color.green(pixel) +
                            android.graphics.Color.blue(pixel)) / 3.0
                    }
                val mean = values.average()
                result = mean to sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            } finally {
                bitmap.recycle()
            }
            if (blur) result.first < 235 && result.second < 9 else result.first > 240
        }
        assertEquals(blur, enabled.get())
        return result
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            advanceFrame()
            Thread.sleep(32)
            if (condition()) return
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $message")
    }
}
