// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassInteractionTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationDragCommitsOnceCancelRestoresAndSemanticsStaySelectable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val app = compose.activity.application as PingClashApplication
        val before = app.repository.data.value
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            // Exercise the spring as well as the production system-motion observer.
            device.executeShellCommand("settings put global animator_duration_scale 1")
            compose.onNodeWithTag("nav-0").performClick().assertIsSelected()
            compose.onNodeWithTag("glass-navigation").performTouchInput {
                down(Offset(width / 8f, centerY))
                moveBy(Offset(width * .52f, 0f), 240)
                up()
            }
            compose.onNodeWithTag("nav-2").assertIsSelected()
            compose.onNodeWithTag("glass-navigation").performTouchInput {
                down(center)
                moveBy(Offset(-width * .45f, 0f), 240)
                cancel()
            }
            compose.onNodeWithTag("nav-2").assertIsSelected()
            compose.onNodeWithTag("nav-0").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("nav-0").assertIsSelected()
            compose.onNodeWithTag("vpn-power").assertIsDisplayed()
            // A cancelled press and navigation never start a VPN or change profile data.
            compose.onNodeWithTag("vpn-power").performTouchInput {
                down(center)
                advanceEventTime(100)
                cancel()
            }
            assertFalse(model.vpn.value.active)
            assertEquals(before, app.repository.data.value)
            captureSyntheticFixture(compose, device, "minimal-home-light.png")
            device.executeShellCommand("settings put global animator_duration_scale 0")
            compose.onNodeWithTag("nav-3").performClick().assertIsSelected()
            compose.onNodeWithTag("nav-0").performClick().assertIsSelected()
            assertFalse(model.vpn.value.active)
        } finally {
            device.executeShellCommand("settings put global animator_duration_scale 0")
        }
    }
}
