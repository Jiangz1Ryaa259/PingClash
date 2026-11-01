// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Test viewport and press-animation sampling.

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageInteractionTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pagesStartAtTheTopInIdleAndActiveStates() {
        val before = VpnRuntime.state.value
        try {
            for (phase in listOf("idle", "connecting", "established", "stopping")) {
                compose.runOnUiThread { VpnRuntime.publish(VpnUiState(phase)) }
                for (tab in 0..3) {
                    compose.onNodeWithTag("nav-$tab").performClick()
                    compose.waitForIdle()
                    // No timer overlay and no gap reserved for one, whatever the VPN phase.
                    assertEquals(
                        0f,
                        compose
                            .onNodeWithTag("scenic-content")
                            .fetchSemanticsNode()
                            .boundsInRoot
                            .top,
                        .1f,
                    )
                }
            }
        } finally {
            compose.runOnUiThread { VpnRuntime.publish(before) }
        }
    }

    @Test
    fun applicationAndConnectionPagesHaveOneScrollOwnerIncludingTheHeading() {
        // The harness's key events leave the device in key mode, where Android focuses the
        // search field on return and scrolls it into view. Restoration is checked for touch.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-apps").performScrollTo().performClick()
        singleScroll()
        compose.onNodeWithText(tr(R.string.settings_system_apps)).performClick()
        compose.waitUntil(10000) {
            compose
                .onNodeWithTag("app-list")
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .maxValue() > 4f
        }
        compose.onNodeWithTag("app-list").performScrollToIndex(4)
        compose.onNodeWithTag("settings-detail-title").assertIsNotDisplayed()
        val position =
            compose
                .onNodeWithTag("app-list")
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings-apps").performScrollTo().performClick()
        val restored = {
            compose
                .onNodeWithTag("app-list")
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
        }
        val returned = runCatching {
            compose.waitUntil(10000) { restored().value() >= position - .1f }
        }
        assertTrue(
            "app list returned to ${restored().value()}/${restored().maxValue()}, left at " +
                "$position; system apps shown=${systemAppsShown()}; rows=" +
                compose.onNodeWithTag("app-list").fetchSemanticsNode().children.size,
            returned.isSuccess,
        )
        compose.onNodeWithTag("app-list").performScrollToIndex(0)
        compose.onNodeWithTag("settings-detail-title").assertIsDisplayed()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-connections").performScrollTo().performClick()
        singleScroll()
        compose.onNodeWithTag("connections-list").assertIsDisplayed()
    }

    @Test
    fun homeProfileNameOpensLiveDetailsAndNeverOffersProfileSwitching() {
        val first = profile("first")
        val second = profile("second")
        var switches = 0
        fixture {
            HomeConfiguration(
                state(AppData(profiles = listOf(first, second))),
                DashboardActions({}, {}, {}, {}, selectProfile = { switches++ }),
            )
        }
        compose.onNodeWithTag("home-profile-picker").assertDoesNotExist()
        compose.onNodeWithTag("home-summary").performClick()
        compose.onNodeWithTag("home-details").assertIsDisplayed()
        compose.onNodeWithText(second.name).assertDoesNotExist()
        assertEquals(0, switches)
    }

    @Test
    fun proxyProfileAndWebsiteCardsShrinkOnPressAndCancelWithoutExecuting() {
        var calls = 0
        fixture {
            ProxyMemberCard(
                ProxyMemberPresentation("Node", "AnyTLS", false, true, null, false),
                ProxyMemberActions({ calls++ }, {}, null, null),
                Modifier.testTag("press-proxy"),
            )
        }
        pressAndCancel("press-proxy")
        assertEquals(0, calls)
        fixture {
            ProfilesOverview(
                AppData(profiles = listOf(profile("press"))),
                false,
                AppUiState(),
                ProfileActions({ calls++ }, { _, _ -> }, {}),
            )
        }
        pressAndCancel("profile-press")
        assertEquals(0, calls)
        fixture { HomeServiceChecks(state(AppData()), { calls++ }) }
        pressAndCancel("service-target-google")
        assertEquals(0, calls)
        compose.onNodeWithTag("service-target-google").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    private fun pressAndCancel(tag: String) {
        compose.mainClock.autoAdvance = false
        try {
            val node = compose.onNodeWithTag(tag)
            val width = node.captureToImage().width
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            assertTrue("$tag has no press feedback", node.captureToImage().width < width)
            node.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals(width, node.captureToImage().width)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun singleScroll() {
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertCountEquals(1)
    }

    private fun profile(id: String) = LocalProfile(id, "Profile $id", null, emptyList(), 0, false)

    private fun state(data: AppData) =
        DashboardState(data, VpnUiState(), DiagnosticUiState(), ProxyTestState(), true, false)

    private fun fixture(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides true,
                        LocalAllowLanyardThree provides false,
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

private fun PageInteractionTest.systemAppsShown() =
    runCatching {
            compose
                .onNodeWithText(tr(R.string.settings_system_apps), useUnmergedTree = false)
                .fetchSemanticsNode()
                .config
                .let {
                    if (it.contains(SemanticsProperties.Selected)) it[SemanticsProperties.Selected]
                    else null
                }
        }
        .getOrNull()
