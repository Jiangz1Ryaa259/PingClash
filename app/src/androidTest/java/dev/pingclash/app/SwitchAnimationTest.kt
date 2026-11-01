// SPDX-License-Identifier: GPL-3.0-only
// Frozen-clock frames, screenshot scales and labels are synthetic UI fixtures.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwitchAnimationTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cancelRapidReverseDisabledAndRtlKeepOneCommittedSwitchState() {
        val value = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var calls = 0
        fixture {
            SettingSwitch("Motion fixture", value.value, enabled.value) {
                value.value = it
                calls++
            }
        }
        val row = compose.onNodeWithTag("switch-Motion fixture")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertHeightIsAtLeast(48.dp)
            .assertIsOff()
        compose.mainClock.autoAdvance = false
        try {
            row.performTouchInput {
                down(center)
                advanceEventTime(80)
                cancel()
            }
            compose.mainClock.advanceTimeBy(80)
            row.assertIsOff()
            compose.runOnIdle { assertEquals(0, calls) }
            row.performClick()
            compose.mainClock.advanceTimeBy(48)
            row.assertIsOn()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            captureSyntheticFixture(compose, device, "switch-motion-mid.png")
            repeat(3) {
                row.performClick()
                compose.mainClock.advanceTimeBy(48)
            }
            compose.mainClock.advanceTimeBy(1000)
            row.assertIsOff()
            compose.runOnIdle {
                assertEquals(4, calls)
                enabled.value = false
            }
            compose.mainClock.advanceTimeByFrame()
            row.assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals(4, calls) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        staticGallery()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        captureSyntheticFixture(compose, device, "switch-motion-static.png")
        assertFalse("Visual controls started a real VPN", VpnRuntime.state.value.active)
    }

    private fun fixture(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(LocalMotionAllowed provides true) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(24.dp)) { content() }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun staticGallery() {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("dark") {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides false,
                        LocalLayoutDirection provides LayoutDirection.Rtl,
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(24.dp)) {
                                SettingSwitch("RTL on", true, true) {}
                                SettingSwitch("RTL off", false, true) {}
                                SettingSwitch("Disabled", false, false) {}
                                VpnPowerControl("idle", true, "Idle") {}
                                VpnPowerControl(
                                    "connecting",
                                    true,
                                    "Connecting · reduced motion",
                                ) {}
                                VpnPowerControl("established", true, "VPN established") {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
