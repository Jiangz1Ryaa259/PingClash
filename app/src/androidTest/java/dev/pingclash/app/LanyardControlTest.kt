// SPDX-License-Identifier: GPL-3.0-only
// Gestures and labels are synthetic; no fixture action starts a real VPN.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
class LanyardControlTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun preparingRendererNeverBlocksThePowerActionOrShowsALoadingCard() {
        var calls = 0
        val preparing = LanyardThreeState()
        fixture {
            CompositionLocalProvider(LocalLanyardThree provides preparing) {
                LanyardPowerControl("idle", true, "Connect fixture") { calls++ }
            }
        }
        assertFalse(preparing.displayReady)
        compose
            .onNodeWithTag("lanyard-minimal-fallback", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("vpn-power").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        assertFalse(VpnRuntime.state.value.active)
    }

    @Test
    fun dragReleaseAndCancelNeverToggleWhileTapCommitsOnce() {
        var calls = 0
        fixture { LanyardPowerControl("idle", true, "Connect fixture") { calls++ } }
        val card = compose.onNodeWithTag("vpn-power")
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val originalCableY =
            compose.onNodeWithTag("window-lanyard-cable").fetchSemanticsNode().config[CableEndY]
        compose.mainClock.autoAdvance = false
        try {
            card.performTouchInput {
                down(center)
                moveBy(Offset(60f, 0f), 160)
                up()
            }
            compose.mainClock.advanceTimeBy(80)
            assertEquals(0f, displacement(), 0f)
            assertEquals(0, calls)
            card.performTouchInput {
                down(center)
                advanceEventTime(800)
                moveBy(Offset(180f, 280f), 160)
            }
            // Recomposition delivers the new target before the animation coroutine's frame.
            compose.mainClock.advanceTimeBy(80)
            assertTrue("Held card did not move: ${displacement()}", displacement() > 5f)
            assertEquals(0, calls)
            val movedCableY =
                compose.onNodeWithTag("window-lanyard-cable").fetchSemanticsNode().config[CableEndY]
            assertTrue(
                "Cable did not follow the card",
                kotlin.math.abs(movedCableY - originalCableY) > 1f,
            )
            card.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1600)
            assertTrue(displacement() < .1f)
            assertEquals(0, calls)
            card.performTouchInput {
                down(center)
                advanceEventTime(800)
                moveBy(Offset(-60f, 0f), 160)
                cancel()
            }
            compose.mainClock.advanceTimeBy(1600)
            assertTrue(displacement() < .1f)
            assertEquals(0, calls)
            card.performTouchInput {
                down(center)
                advanceEventTime(800)
                up()
            }
            compose.mainClock.advanceTimeBy(80)
            assertEquals(0, calls)
            card.performTouchInput { click() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(1, calls) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertFalse(VpnRuntime.state.value.active)
        capture("lanyard-control.png")
    }

    @Test
    fun reducedMotionAndBusyStatesKeepAnAccessibleSingleAction() {
        var calls = 0
        val phase = mutableStateOf("idle")
        val enabled = mutableStateOf(true)
        fixture(motion = false) {
            LanyardPowerControl(phase.value, enabled.value, "Connect fixture") { calls++ }
        }
        val card = compose.onNodeWithTag("vpn-power")
        card.performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(65f, 20f), 180)
            up()
        }
        assertEquals(0, calls)
        assertEquals(0f, displacement(), 0f)
        card.performClick()
        assertEquals(1, calls)
        compose.runOnIdle { phase.value = "stopping" }
        card.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, calls)
        compose.runOnIdle {
            phase.value = "idle"
            enabled.value = false
        }
        card.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, calls)
        capture("lanyard-disabled.png")
    }

    @Test
    fun largeTextRtlAndConnectedFaceRemainVisibleAndClickable() {
        var calls = 0
        fixture(motion = false, large = true) {
            LanyardPowerControl("established", true, "断开 VPN 连接") { calls++ }
        }
        val card = compose.onNodeWithTag("vpn-power").performScrollTo()
        val cable = compose.onNodeWithTag("window-lanyard-cable").fetchSemanticsNode()
        assertEquals(0f, cable.boundsInRoot.top, .1f)
        assertTrue(cable.config[CableEndY] > 0f)
        compose
            .onNodeWithTag("lanyard-minimal-fallback", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("断开 VPN 连接").assertIsDisplayed()
        card.assertHeightIsAtLeast(300.dp).performTouchInput { click() }
        assertEquals(1, calls)
        capture("lanyard-large-rtl.png")
        assertFalse(VpnRuntime.state.value.active)
    }

    @Test
    fun turningOnFlipsImmediatelyAndStoppingOrErrorReturnsToFront() {
        val phase = mutableStateOf("idle")
        fixture {
            LanyardPowerControl(phase.value, true, "Toggle fixture") { phase.value = "connecting" }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("vpn-power").performClick()
            compose.mainClock.advanceTimeBy(1600)
            assertEquals(180f, faceAngle(), .1f)
            compose.runOnIdle { phase.value = "established" }
            compose.mainClock.advanceTimeBy(80)
            assertEquals(180f, faceAngle(), .1f)
            compose.runOnIdle { phase.value = "stopping" }
            compose.mainClock.advanceTimeBy(1600)
            assertEquals(0f, faceAngle(), .1f)
            compose.runOnIdle { phase.value = "error" }
            compose.mainClock.advanceTimeBy(80)
            assertEquals(0f, faceAngle(), .1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertFalse(VpnRuntime.state.value.active)
    }

    private fun faceAngle() =
        compose.onNodeWithTag("lanyard-stage").fetchSemanticsNode().config[LanyardFaceAngle]

    private fun displacement() =
        compose.onNodeWithTag("lanyard-stage").fetchSemanticsNode().config[LanyardDisplacement]

    private fun capture(name: String) =
        captureSyntheticFixture(
            compose,
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            name,
        )

    private fun fixture(
        motion: Boolean = true,
        large: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("dark") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalMotionAllowed provides motion,
                        LocalAllowLanyardThree provides false,
                        LocalDensity provides Density(density.density, if (large) 1.8f else 1f),
                        LocalLayoutDirection provides
                            if (large) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        ScenicFrame(0, "none", true, {}) {
                            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                                Column(
                                    Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 24.dp, vertical = 40.dp)
                                ) {
                                    content()
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
