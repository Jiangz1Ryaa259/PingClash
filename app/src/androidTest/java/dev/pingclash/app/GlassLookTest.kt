// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The glass pages in the running app, saved as `glass-*.png` in the app's external files for a
 * look: on Android 13+ the panels blur the live scene behind them.
 */
@RunWith(AndroidJUnit4::class)
class GlassLookTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDiagnosticsAndTheAddSheet() {
        compose.onNodeWithTag("nav-3").performClick()
        settle()
        shot("settings")
        visibleSettingsControl(compose, "tool-diagnosis").performClick()
        settle()
        shot("diagnostics")
        compose.onNodeWithTag("diagnosis-back").performClick()
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("profile-add").performClick()
        settle()
        shot("add-sheet")
        compose.onNodeWithTag("import-group-file").performClick()
        settle()
        shot("add-menu")
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(900)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "glass-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
