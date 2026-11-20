// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Proxies ⋮ menu frame by frame on a held clock, opening and closing, saved as `reveal-*.png`
 * in the app's external files for a look. Motion is on here.
 */
@RunWith(AndroidJUnit4::class)
class RevealMenuLookTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aCircleOfLightUncoversTheMenu() {
        var close: () -> Unit = {}
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(LocalMotionAllowed provides true) {
                        var open by remember { mutableStateOf(false) }
                        close = { open = false }
                        Box(
                            Modifier.fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFFD7E7F2),
                                            Color(0xFFC3DCEA),
                                            Color(0xFF8DBCD0),
                                            Color(0xFF3F7F97),
                                        )
                                    )
                                )
                                .padding(top = 120.dp, end = 12.dp),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Box {
                                RevealMenuButton(
                                    open,
                                    { open = it },
                                    Modifier.testTag("reveal-button"),
                                ) {
                                    listOf(
                                            "Scroll to selected",
                                            "Style",
                                            "Sort",
                                            "Layout",
                                            "Size",
                                            "Hide timed-out",
                                        )
                                        .forEach { GlassMenuItem({ Text(it) }, { open = false }) }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reveal-button").performClick()
        frames("open", listOf(32L, 64L, 96L, 144L, 200L, 600L))
        compose.runOnUiThread { close() }
        frames("close", listOf(32L, 64L, 96L, 128L, 176L, 600L))
        compose.mainClock.autoAdvance = true
    }

    private fun frames(name: String, at: List<Long>) {
        var now = 0L
        at.forEach { frame ->
            compose.mainClock.advanceTimeBy(frame - now)
            now = frame
            Thread.sleep(300)
            shot("$name-$frame")
        }
    }

    private fun shot(name: String) {
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "reveal-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
