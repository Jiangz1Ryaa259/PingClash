// SPDX-License-Identifier: GPL-3.0-only
// Only synthetic flat colors are placed in the task snapshot.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecentPreviewTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun ordinaryTaskHasPreviewWhileSecureContentStaysHidden() {
        val owner = Any()
        paint(Color.Cyan)
        try {
            assertTrue(device.pressRecentApps())
            val ordinary = countColor("recent-public.png", android.graphics.Color.CYAN)
            assertTrue("Normal task preview was blank: $ordinary", ordinary > 10000)
            device.pressBack()
            compose.waitForIdle()
            compose.runOnUiThread { SecurePage.acquire(compose.activity.window, owner) }
            paint(Color.Red)
            compose.runOnIdle {
                assertTrue(
                    compose.activity.window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0
                )
            }
            assertTrue(device.pressRecentApps())
            val secret =
                countColor("recent-protected.png", android.graphics.Color.RED, allowBlocked = true)
            assertTrue("Protected content entered the task snapshot: $secret", secret < 1000)
        } finally {
            device.pressBack()
            compose.runOnUiThread { SecurePage.release(compose.activity.window, owner) }
        }
    }

    private fun paint(color: Color) {
        compose.runOnUiThread {
            compose.activity.setContent { Box(Modifier.fillMaxSize().background(color)) }
        }
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun countColor(name: String, color: Int, allowBlocked: Boolean = false): Int {
        Thread.sleep(1200)
        val file = File(compose.activity.getExternalFilesDir(null), "synthetic-test-evidence/$name")
        file.parentFile?.mkdirs()
        val captured = device.takeScreenshot(file)
        if (!captured && allowBlocked) {
            assertTrue(
                compose.activity.window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            println(
                "Protected task capture rejected by Android; ordinary preview capture is still required"
            )
            return 0
        }
        assertTrue("Ordinary task capture failed", captured)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            return matchingPixels(bitmap, color)
        } finally {
            bitmap.recycle()
        }
    }

    private fun matchingPixels(bitmap: android.graphics.Bitmap, color: Int): Int {
        var count = 0
        for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step 2) {
            for (x in bitmap.width / 5 until bitmap.width * 4 / 5 step 2) {
                val pixel = bitmap.getPixel(x, y)
                val difference =
                    kotlin.math.abs(
                        android.graphics.Color.red(pixel) - android.graphics.Color.red(color)
                    ) +
                        kotlin.math.abs(
                            android.graphics.Color.green(pixel) -
                                android.graphics.Color.green(color)
                        ) +
                        kotlin.math.abs(
                            android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color)
                        )
                if (difference < 25) count++
            }
        }
        return count
    }
}
