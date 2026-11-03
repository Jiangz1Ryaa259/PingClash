// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Real window/capture assertions on synthetic input only.

package dev.pingclash.app

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorCaptureTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun importer(): ProfileImportActivity {
        lateinit var activity: ProfileImportActivity
        instrumentation.runOnMainSync {
            activity =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ProfileImportActivity>()
                    .single()
        }
        return activity
    }

    private fun protected(activity: ProfileImportActivity): Boolean {
        var result = false
        instrumentation.runOnMainSync {
            result =
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        return result
    }

    @Test
    fun privateInputIsMaskedBeforeErrorCaptureAndCopiedSummaryIsSafe() {
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("profile-add").performClick()
        compose.onNodeWithTag("import-method-url").performClick()
        compose.waitUntil(5000) {
            runCatching {
                    compose.onNodeWithTag("import-page").assertIsDisplayed()
                    true
                }
                .getOrDefault(false)
        }
        val activity = importer()
        try {
            compose
                .onNodeWithTag("subscription-content")
                .performTextInput("https://example.invalid/feed?token=CANARY-SUB-TOKEN-1234")
            hideFixtureKeyboard()
            assertFalse(protected(activity))
            compose.onNodeWithContentDescription("显示").performClick()
            compose.waitUntil(5000) { protected(activity) }
            val message = ProviderFailure(1, "rule", "provider_http_403").publicMessage()
            instrumentation.runOnMainSync {
                ViewModelProvider(activity)[AppViewModel::class.java]
                    .notices
                    .publish(
                        IssueNotice(
                            "capture-fixture",
                            tr(R.string.issue_warning),
                            message,
                            IssueStage.PROVIDER,
                            IssueSeverity.WARNING,
                            "import-warning",
                        )
                    )
            }
            compose.onNodeWithTag("import-warning").performClick()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.waitUntil(5000) { !protected(activity) }
            compose
                .onNodeWithTag("subscription-content", useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithTag("issue-copy").performClick()
            instrumentation.runOnMainSync {
                val clip =
                    activity
                        .getSystemService(android.content.ClipboardManager::class.java)
                        .primaryClip
                val text = clip!!.getItemAt(0).text.toString()
                assertTrue(text.contains("403"))
                assertFalse(text.contains("CANARY-"))
                assertFalse(text.contains("example.invalid"))
                assertFalse(text.contains("token="))
            }
            assertSafeFrame(activity)
            compose.onNodeWithText(tr(R.string.close)).performClick()
            compose.waitUntil(5000) { protected(activity) }
            compose.onNodeWithContentDescription("隐藏").performClick()
            compose.waitUntil(5000) { !protected(activity) }
            assertEquals(
                0,
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
            )
            compose.onNodeWithText("节点配置").performClick()
            compose
                .onNodeWithTag("subscription-content")
                .performTextInput("{\"password\":\"CANARY-PASSWORD-1234\"}")
            hideFixtureKeyboard()
            compose.waitUntil(5000) { protected(activity) }
            instrumentation.runOnMainSync {
                ViewModelProvider(activity)[AppViewModel::class.java]
                    .notices
                    .publish(
                        IssueNotice(
                            "raw-capture-fixture",
                            tr(R.string.issue_warning),
                            message,
                            IssueStage.PROVIDER,
                            IssueSeverity.WARNING,
                            "import-warning",
                        )
                    )
            }
            compose.onNodeWithTag("import-warning").performClick()
            compose.waitUntil(5000) { !protected(activity) }
            compose
                .onNodeWithTag("subscription-content", useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            assertSafeFrame(activity)
            compose.onNodeWithText(tr(R.string.close)).performClick()
            compose.waitUntil(5000) { protected(activity) }
        } finally {
            instrumentation.runOnMainSync {
                activity
                    .getSystemService(android.content.ClipboardManager::class.java)
                    .clearPrimaryClip()
                activity.finish()
            }
        }
    }

    private fun assertSafeFrame(activity: ProfileImportActivity) {
        UiDevice.getInstance(instrumentation).waitForIdle(1000)
        Thread.sleep(300)
        val frame = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Safe error capture was denied", frame)
        val screenshot = requireNotNull(frame)
        var bright = 0
        for (x in 100 until screenshot.width step 20) for (y in
            100 until screenshot.height step 20) {
            if (android.graphics.Color.red(screenshot.getPixel(x, y)) > 100) bright++
        }
        assertTrue("Safe error capture was black", bright > 100)
        java.io
            .File(activity.getExternalFilesDir(null), "provider-error-capture.png")
            .outputStream()
            .use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
