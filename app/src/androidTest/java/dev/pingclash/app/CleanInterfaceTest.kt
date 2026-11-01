// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanInterfaceTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun rootTitlesShareTypographyAndToolsUseTheSettingsList() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val specs = mutableListOf<Pair<Float, FontWeight?>>()
        listOf("home", "proxies", "profiles", "settings").forEachIndexed { index, id ->
            compose.onNodeWithTag("nav-$index").performClick()
            compose.onNodeWithTag("page-title-$id").assertIsDisplayed().performSemanticsAction(
                SemanticsActions.GetTextLayoutResult
            ) { get ->
                val result = mutableListOf<TextLayoutResult>()
                assertTrue(get(result))
                val style = result.single().layoutInput.style
                specs.add(style.fontSize.value to style.fontWeight)
            }
        }
        assertEquals(1, specs.distinct().size)
        assertEquals(22f, specs.first().first, 0f)
        assertEquals(FontWeight.Bold, specs.first().second)
        assertTrue(compose.onAllNodesWithTag("tool-settings").fetchSemanticsNodes().isEmpty())
        visibleSettingsControl(compose, "tool-terminal").assertHasClickAction()
        visibleSettingsControl(compose, "tool-diagnosis").performClick()
        compose.onNodeWithTag("diagnosis-back").assertIsDisplayed().performClick()
        // Diagnosis now restores the previous scroll position; the root title
        // can legitimately remain above the viewport until explicitly scrolled.
        compose.onNodeWithTag("page-title-settings").assertExists()
        compose.onNodeWithTag("settings-page").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, -10000f)
        }
        compose.onNodeWithTag("page-title-settings").assertIsDisplayed()
        captureSyntheticFixture(compose, device, "clean-settings-light.png")
        visibleSettingsControl(compose, "settings-network").performClick()
        compose.onNodeWithTag("settings-back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("page-title-settings").assertIsDisplayed()
    }

    @Test
    fun ordinaryRulePageRemainsCapturableThroughItsRightwardTransition() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("settings put global animator_duration_scale 1")
        try {
            compose.onNodeWithTag("nav-3").performClick()
            compose.waitForIdle()
            visibleSettingsControl(compose, "settings-rules").performClick()
            compose.waitForIdle()
            assertFalse(secure())
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("settings-back").performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            assertFalse("Ordinary rule metadata unexpectedly blocks capture", secure())
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { !secure() }
            assertFalse(secure())
        } finally {
            compose.mainClock.autoAdvance = true
            device.executeShellCommand("settings put global animator_duration_scale 0")
        }
    }

    @Test
    fun privatePixelsRetainProtectionUntilTheirExitTransitionFinishes() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val shown = androidx.compose.runtime.mutableStateOf(true)
        device.executeShellCommand("settings put global animator_duration_scale 1")
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    androidx.compose.animation.AnimatedVisibility(
                        shown.value,
                        exit =
                            androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(320)
                            ),
                    ) {
                        VisiblePrivateContent(true)
                        androidx.compose.material3.Text("CANARY-PRIVATE-PIXELS")
                    }
                }
            }
            compose.waitForIdle()
            assertTrue(secure())
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { shown.value = false }
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            assertTrue("Visible private exit frames lost protection", secure())
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { !secure() }
        } finally {
            compose.mainClock.autoAdvance = true
            device.executeShellCommand("settings put global animator_duration_scale 0")
        }
    }

    private fun secure() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}

internal fun visibleSettingsControl(
    compose: AndroidComposeTestRule<*, MainActivity>,
    tag: String,
): SemanticsNodeInteraction {
    val node = compose.onNodeWithTag(tag).performScrollTo()
    val top = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot.top
    val clearance = 12 * compose.activity.resources.displayMetrics.density
    val distance = node.fetchSemanticsNode().boundsInRoot.bottom - top + clearance
    if (distance > 0)
        compose.onNodeWithTag("settings-page").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, distance)
        }
    compose.waitForIdle()
    assertTrue(node.fetchSemanticsNode().boundsInRoot.bottom <= top)
    return node
}
