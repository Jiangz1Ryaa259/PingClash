// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selected profile card's light after a few seconds, at each level, in light and dark; saved as
 * `liquid-*.png` in the app's external files for a look. A UI test turns the endless animation off,
 * so the frames are stepped here by hand.
 */
@RunWith(AndroidJUnit4::class)
class LiquidLightLookTest {
    @get:Rule val compose = mainThreadComposeRule<MainActivity>()

    private val frame = 16_000_000L

    private fun show(theme: String, lean: Float) {
        val dark = theme == "dark"
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme(theme, false) {
                    Column(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listOf(.72f, .31f, .12f).forEachIndexed { index, left ->
                            val level = quotaLevel(left)
                            val color = quotaLevelColor(level, dark)
                            val liquid = LiquidLight()
                            val colors = LiquidColors(color, dark, level == QuotaLevel.CRITICAL)
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(96.dp)
                                    .testTag("liquid-$index")
                                    .clip(UiCorners.Sheet)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .drawBehind {
                                        if (liquid.frame == 0L) {
                                            liquid.pour()
                                            repeat(260) {
                                                liquid.step(
                                                    (it + 1) * frame,
                                                    size,
                                                    left,
                                                    density,
                                                    lean,
                                                )
                                            }
                                        }
                                        with(liquid) { drawLiquid(colors) }
                                    }
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("liquid-0").assertExists()
    }

    @Test
    fun theLightAtEachLevelInBothThemes() {
        listOf("dark", "light").forEach { theme ->
            show(theme, lean = 0f)
            shot("$theme")
        }
        show("dark", lean = .8f)
        shot("dark-leaning")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "liquid-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
