// SPDX-License-Identifier: GPL-3.0-only
// Synthetic UI/clipboard fixtures; never requests public service endpoints.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractionUpgradeTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun homeHasThreeSectionsAndTestsRequireAnExplicitAction() {
        val original = app.repository.data.value.client.mode
        try {
            VpnRuntime.publish(VpnUiState("established"))
            app.repository.routeMode("direct")
            assertEquals("direct", app.repository.data.value.client.mode)
            assertTrue(
                runCatching {
                        app.repository.clientSettings(
                            ClientSettings(),
                            "1.1.1.1",
                            "https://www.gstatic.com/generate_204",
                        )
                    }
                    .isFailure
            )
        } finally {
            VpnRuntime.publish(VpnUiState())
            app.repository.routeMode(original)
        }
        var calls = 0
        var mode = ""
        val profile = profile("one")
        fixture {
            DashboardScreen(
                DashboardState(
                    AppData(listOf(profile), selectedId = "node"),
                    VpnUiState(uptime = 65),
                    DiagnosticUiState(),
                    ProxyTestState(),
                    true,
                    false,
                ),
                DashboardActions({}, {}, {}, {}, mode = { mode = it }, serviceTest = { calls++ }),
            )
        }
        compose.onNodeWithTag("home-control").assertExists()
        compose.onNodeWithTag("home-configuration").assertExists()
        compose.onNodeWithTag("home-service-checks").assertExists()
        compose.onNodeWithTag("dashboard-diagnose").assertDoesNotExist()
        assertEquals(0, calls)
        // The dial only reports a change of mode; tapping the current one does nothing.
        clickHomeControl("mode-global")
        // The mode is requested when the wheel reaches it, a moment after the tap.
        compose.waitUntil(3000) { mode == "global" }
        clickHomeControl("service-check-start")
        assertEquals(1, calls)
        compose.onNodeWithText("Google").assertExists()
        compose.onNodeWithText("ChatGPT").assertExists()
        compose.onNodeWithTag("theme-toggle").assertDoesNotExist()
        compose.onNodeWithTag("service-check-more").performClick()
        assertEquals(1, calls)
        compose.onNodeWithTag("service-detail-gemini").assertExists()
        compose.onNodeWithTag("service-detail-github").assertExists()
    }

    private fun clickHomeControl(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }

    @Test
    fun profileCopyIsSensitiveAndCollapsedAddRemainsAccessible() {
        var selected = ""
        var adds = 0
        val rows = (1..18).map { profile(it.toString()) }
        fixture {
            ProfilesOverview(
                AppData(profiles = rows),
                false,
                AppUiState(),
                ProfileActions({ selected = it.id }, { _, _ -> }, { adds++ }),
            )
        }
        compose.onNodeWithTag("profile-1").performClick()
        assertEquals("1", selected)
        compose.onNodeWithTag("profile-menu-1").performClick()
        compose.onNodeWithText("复制订阅链接").performClick()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(rows.first().source, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        assertTrue(
            clipboard.primaryClipDescription
                ?.extras
                ?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        )
        compose.onNodeWithText("已复制").assertExists()
        compose.onNodeWithText("CANARY-SUB-TOKEN", substring = true).assertDoesNotExist()
        device.pressBack()
        compose.onNodeWithTag("profiles-list").performScrollToIndex(12)
        compose.onNodeWithTag("profile-add").assertHasClickAction().performClick()
        compose.onNodeWithTag("import-method-url").performClick()
        assertEquals(1, adds)
        clipboard.clearPrimaryClip()
    }

    @Test
    fun onlyAnUnactivatedLinkImportShowsTheNewChip() {
        val rows =
            mutableStateOf(listOf(profile("1").copy(activationRequired = true), profile("2")))
        fixture {
            ProfilesOverview(
                AppData(profiles = rows.value),
                false,
                AppUiState(),
                ProfileActions({}, { _, _ -> }, {}),
            )
        }
        compose.onAllNodesWithTag("profile-new", useUnmergedTree = true).assertCountEquals(1)
        compose
            .onNode(hasTestTag("profile-new") and hasAnyAncestor(hasTestTag("profile-1")), true)
            .assertExists()
        // Selecting activates the import; the chip goes with the flag.
        compose.runOnIdle { rows.value = rows.value.map { it.copy(activationRequired = false) } }
        compose.onAllNodesWithTag("profile-new", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun invalidLinksNeverDownloadAndTheHostIsShownBeforeTheOneTap() {
        var submits = 0
        fixture { ImportDialog(AppUiState(), {}, onSubmit = { _, _, _ -> submits++ }) }
        compose
            .onNodeWithTag("subscription-content")
            .performTextInput("http://fixture.invalid/feed")
        hideFixtureKeyboard()
        compose.onNodeWithTag("import-submit").performScrollTo().performClick()
        assertEquals(0, submits)
        compose
            .onNodeWithText(tr(R.string.enter_a_complete_https_subscription_link_w))
            .assertExists()
        // A loose but valid link: an underscore host, a pipe and a provider's #name.
        compose
            .onNodeWithTag("subscription-content")
            .performTextReplacement("https://my_sub.fixture.invalid/feed?a=1|2#Airport")
        hideFixtureKeyboard()
        compose
            .onNodeWithText(tr(R.string.ui_import_downloads_from, "my_sub.fixture.invalid"))
            .assertExists()
        assertEquals(0, submits)
        compose.onNodeWithTag("import-submit").performScrollTo().performClick()
        assertEquals(1, submits)
    }

    @Test
    fun themeRevealIsTransientAndSkipsProtectedWindows() {
        device.executeShellCommand("settings put global animator_duration_scale 1")
        val mode = mutableStateOf("light")
        lateinit var controller: ThemeRevealController
        compose.runOnUiThread {
            compose.activity.setContent {
                ThemeRevealHost(mode.value, false) {
                    val local = LocalThemeReveal.current
                    SideEffect { controller = requireNotNull(local) }
                    PingClashTheme(mode.value) {
                        Surface(Modifier.fillMaxSize(), color = Ink) {
                            Column {
                                ThemeToggle(ClientSettings(theme = mode.value)) { mode.value = it }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("theme-toggle").performClick()
            compose.waitUntil(8000) { controller.bitmap != null }
            assertNotNull(controller.bitmap)
            compose.mainClock.advanceTimeBy(850)
            compose.waitForIdle()
            assertNull(controller.bitmap)
            assertEquals("dark", mode.value)
            checkProtectedReveal(controller) { mode.value = "light" }
            assertEquals("light", mode.value)
        } finally {
            compose.mainClock.autoAdvance = true
            device.executeShellCommand("settings put global animator_duration_scale 0")
        }
    }

    private fun checkProtectedReveal(controller: ThemeRevealController, change: () -> Unit) {
        val owner = Any()
        compose.runOnUiThread {
            SecurePage.acquire(compose.activity.window, owner)
            controller.request(Offset(20f, 20f), change)
            assertNull(controller.bitmap)
            SecurePage.release(compose.activity.window, owner)
        }
    }

    private fun profile(id: String) =
        LocalProfile(
            id,
            "Fixture $id",
            "https://fixture.invalid/feed?token=CANARY-SUB-TOKEN-$id",
            listOf(LocalNode("node", "Fixture node", "socks5", "{}")),
            0,
            false,
            quota = SubscriptionQuota(10, 20, 100, 1800000000),
        )

    private fun fixture(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(
                        LocalAllowLanyardThree provides false,
                        LocalMotionAllowed provides false,
                    ) {
                        ScenicFrame(0, "none", true, {}) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
