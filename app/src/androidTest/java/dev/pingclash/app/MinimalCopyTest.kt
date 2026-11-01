// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinimalCopyTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun profileEmptyStateAndSelectionRemainClearWithoutExplanatoryCopy() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var data by mutableStateOf(AppData())
        var additions = 0
        var chosen = ""
        val profile =
            LocalProfile(
                "minimal",
                "Fixture profile",
                null,
                listOf(LocalNode("minimal-node", "Fixture node", "socks5", "{}")),
                0,
                false,
            )
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                PingClashTheme("dark") {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, 2f),
                        LocalMotionAllowed provides false,
                    ) {
                        ScenicFrame(2, "none", true, {}) {
                            ProfilesOverview(
                                data,
                                false,
                                AppUiState(),
                                ProfileActions(
                                    select = {
                                        chosen = it.id
                                        data = data.copy(activeProfileId = it.id)
                                    },
                                    command = { _, _ -> },
                                    add = { additions++ },
                                ),
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("profiles-empty").assertIsDisplayed()
        compose.onNodeWithText("让连接从这里开始").assertDoesNotExist()
        compose.onNodeWithTag("profile-add").performClick()
        compose.onNodeWithTag("import-method-url").performClick()
        assertEquals(1, additions)
        captureSyntheticFixture(compose, device, "minimal-profiles-empty-large-dark.png")
        compose.runOnIdle {
            data =
                AppData(
                    profiles = listOf(profile, profile.copy(id = "other", name = "Other fixture")),
                    activeProfileId = "other",
                )
        }
        val card = compose.onNodeWithTag("profile-minimal")
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        card.assertIsNotSelected().performClick().assertIsSelected()
        assertEquals("minimal", chosen)
        compose.onNodeWithText("查看代理").assertDoesNotExist()
        compose.onNodeWithTag("profile-quota-minimal", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("profile-menu-minimal").assertHasClickAction()
        captureSyntheticFixture(compose, device, "minimal-profile-selected-large-dark.png")
        compose.runOnIdle {
            data =
                data.copy(
                    profiles =
                        listOf(
                            profile.copy(
                                quota = SubscriptionQuota(upload = 10, download = 20, total = 100)
                            )
                        )
                )
        }
        compose.onNodeWithTag("profile-quota-minimal", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("minimal", chosen)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun settingsHaveSingleKeyboardTargetsAndAuxiliaryChecksHaveNoLegacyMasthead() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // A previous synthetic subscription may require the genuine one-time
        // certificate notice. Do not test keyboard focus through an open modal.
        val notice = compose.onAllNodesWithText(tr(R.string.understood_do_not_remind_again))
        if (notice.fetchSemanticsNodes().isNotEmpty()) notice.onFirst().performClick()
        compose.onNodeWithTag("nav-3").performClick()
        visibleSettingsControl(compose, "settings-dns")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onAllNodes(radio).assertCountEquals(2)
        val https = compose.onNodeWithTag("choice-模式-https").performScrollTo()
        // Enter keyboard input mode before requesting a non-text control's focus.
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_TAB)
        compose.waitForIdle()
        https.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        https.performKeyInput { pressKey(Key.Enter) }
        https.assertIsSelected()
        compose.onNodeWithTag("field-加密 DNS 地址").assertExists()
        compose.onNodeWithTag("settings-back").performClick()
        visibleSettingsControl(compose, "settings-network").performClick()
        val mtu = compose.onNodeWithTag("number-MTU").performScrollTo()
        compose.onNodeWithText("1280–9000").assertDoesNotExist()
        // MTU is now a range-constrained slider, not a free-text editor.
        val repo = (compose.activity.application as PingClashApplication).repository
        val before = repo.data.value
        mtu.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1400f)) }
        compose.waitUntil(10000) { repo.data.value.client.mtu == 1400 }
        repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        hideFixtureKeyboard()
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        visibleSettingsControl(compose, "tool-diagnosis").performClick()
        compose.onNodeWithTag("diagnostic-auxiliary").performScrollTo().performClick()
        compose.onNodeWithTag("auxiliary-title").assertIsDisplayed()
        compose.onNodeWithText("0.2 · 辅助探测").assertDoesNotExist()
        compose.onNodeWithText("证据时间线").assertDoesNotExist()
        compose.onNodeWithTag("auxiliary-host").assertIsDisplayed()
        captureSyntheticFixture(compose, device, "minimal-auxiliary-empty-light.png")
        compose.onNodeWithText("开始诊断").performScrollTo().performClick()
        compose.onNodeWithText(tr(R.string.diagnostic_624c9062b5)).assertExists()
        compose.onNodeWithTag("auxiliary-back").performScrollTo().performClick()
        compose.onNodeWithTag("diagnosis-back").assertIsDisplayed()
        device.pressBack()
        compose.waitUntil(5000) {
            runCatching {
                    compose.onNodeWithTag("settings-page").assertExists()
                    true
                }
                .getOrDefault(false)
        }
        compose.onNodeWithTag("settings-page").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, -10000f)
        }
        compose.onNodeWithTag("page-title-settings").assertIsDisplayed()
    }
}
