// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A menu uncovered by a circle spreading from the button that opened it, frame by frame on a held
 * clock, saved as `morph-<ms>.png` in the app's external files for a look. Motion is on here
 * whatever the device's animation setting.
 */
@RunWith(AndroidJUnit4::class)
class MenuMorphLookTest {
    @get:Rule val compose = mainThreadComposeRule<MainActivity>()

    @Test
    fun aMenuOpensOutOfItsButton() {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(LocalMotionAllowed provides true) {
                        var open by remember { mutableStateOf(false) }
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(top = 260.dp, end = 24.dp),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Box {
                                GlassButton({ open = true }, Modifier.testTag("menu-anchor")) {
                                    Text("VLESS")
                                }
                                GlassMenu(open, { open = false }) {
                                    listOf(
                                            "VLESS",
                                            "VMess",
                                            "Trojan",
                                            "Shadowsocks",
                                            "Hysteria2",
                                            "TUIC",
                                            "AnyTLS",
                                            "SOCKS5",
                                            "HTTP",
                                            "WireGuard",
                                        )
                                        .forEach { name ->
                                            GlassMenuItem({ Text(name) }, { open = false })
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("menu-anchor").performClick()
        var at = 0L
        listOf(16L, 64L, 112L, 176L, 256L, 400L, 800L).forEach { frame ->
            compose.mainClock.advanceTimeBy(frame - at)
            at = frame
            Thread.sleep(300)
            shot("open-$frame")
        }
        compose.mainClock.autoAdvance = true
        // A long menu shows every item in full once open, the last as well as the first.
        compose.onNodeWithText("WireGuard").assertIsDisplayed()
    }

    private fun shot(name: String) {
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "morph-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
