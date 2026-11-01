// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber", "LongMethod") // Bounded real-IME and explicit sharing journeys.

package dev.pingclash.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalInteractionTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<TerminalActivity>()
    private val model
        get() = compose.activity.terminal

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun output() = terminalOutput(model.sessions.firstOrNull())

    private fun imeVisible() =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Before
    fun startSession() {
        if (!model.noticeAccepted) compose.onNodeWithTag("terminal-notice-accept").performClick()
        compose.waitForIdle()
        if (model.sessions.isEmpty()) compose.onNodeWithTag("terminal-new").performClick()
        compose.waitUntil(10000) {
            model.sessions.firstOrNull()?.pid?.let { it > 0 } == true && model.view != null
        }
    }

    @Test
    fun terminalTapAndKeyboardButtonShowImeAndCommitCommandsAndScreenshotsWork() {
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        compose.onNodeWithTag("terminal-view").performTouchInput { click(center) }
        compose.waitUntil(10000) { imeVisible() }
        assertTrue(requireNotNull(model.view).isFocusableInTouchMode)
        compose.runOnUiThread {
            val editor = EditorInfo()
            val input = requireNotNull(model.view?.onCreateInputConnection(editor))
            assertEquals(InputType.TYPE_CLASS_TEXT, editor.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(
                "Terminal must not request any password keyboard",
                InputType.TYPE_TEXT_VARIATION_NORMAL,
                editor.inputType and InputType.TYPE_MASK_VARIATION,
            )
            assertTrue(editor.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
            assertTrue(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            input.commitText("printf 'IME_%s\\n' OK\n", 1)
        }
        compose.waitUntil(5000) { output().contains("IME_OK") }
        compose.runOnUiThread {
            ViewCompat.getWindowInsetsController(requireNotNull(model.view))
                ?.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10000) { !imeVisible() }
        assertTerminalScreenshot()
        compose.onNodeWithTag("terminal-keyboard").performClick()
        compose.waitUntil(10000) { imeVisible() }
        assertTerminalScreenshot()
    }

    @Test
    fun resumedTerminalClearsStaleSecureFlagAndRemainsCapturable() {
        compose.runOnUiThread { model.send("printf 'RESUME_%s\\n' OK\r") }
        compose.waitUntil(5000) { output().contains("RESUME_OK") }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertTerminalScreenshot()
    }

    private fun assertTerminalScreenshot() {
        assertEquals(
            0,
            compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Native terminal drawing and the secure-layer transaction can commit
        // after Compose becomes idle. Request a complete frame before capture,
        // like the existing software-emulator screenshot fixture helper.
        compose.runOnUiThread { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        UiDevice.getInstance(instrumentation).waitForIdle(1000)
        Thread.sleep(300)
        val screen =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                "Terminal screenshot unavailable"
            }
        val location = IntArray(2)
        compose.runOnUiThread { model.view!!.getLocationOnScreen(location) }
        val view = requireNotNull(model.view)
        var textPixels = 0
        for (y in location[1] until minOf(location[1] + view.height, screen.height) step 2) {
            for (x in location[0] until minOf(location[0] + view.width, screen.width) step 2) {
                val pixel = screen.getPixel(x, y)
                if (
                    android.graphics.Color.red(pixel) > 150 &&
                        android.graphics.Color.green(pixel) > 150
                )
                    textPixels++
            }
        }
        screen.recycle()
        assertTrue("Screenshot hid the terminal output", textPixels > 20)
    }

    @Test
    fun copyIsExplicitAndSharePreviewsLiteralOutputBeforeSystemChooser() {
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { model.send("printf 'SHARE_%s\\n' OK\r") }
        compose.waitUntil(5000) { output().contains("SHARE_OK") }
        compose.onNodeWithTag("terminal-copy").performClick()
        compose.runOnUiThread {
            val clip = requireNotNull(clipboard.primaryClip)
            assertTrue(clip.getItemAt(0).text?.contains("SHARE_OK") == true)
            assertTrue(
                clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
            )
        }
        val monitor =
            instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_CHOOSER),
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
                true,
            )
        try {
            compose.onNodeWithTag("terminal-share").performClick()
            compose.onNodeWithTag("terminal-share-preview").assertIsDisplayed()
            assertEquals(0, monitor.hits)
            compose.onNodeWithTag("terminal-share-cancel").performClick()
            assertEquals(0, monitor.hits)
            compose.onNodeWithTag("terminal-share").performClick()
            compose.onNodeWithTag("terminal-share-confirm").performClick()
            compose.waitUntil(5000) { monitor.hits == 1 }
            val intent = terminalShareIntent(output())
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().contains("SHARE_OK"))
            assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
            assertFalse(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
