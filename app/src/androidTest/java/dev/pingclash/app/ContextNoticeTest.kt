// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.diagnostics.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextNoticeTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun fiveLockedSettingsPagesExplainTheLockOnlyInTheOverlayAndClearItOnExit() = manualClock {
        val center = IssueNoticeCenter()
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        fixture(center) {
            SettingsHub(
                AppData(),
                VpnUiState("established", sessionStartedElapsed = 123),
                true,
                model,
                {},
            )
        }
        settle()
        for (page in listOf("network", "dns", "apps", "rules", "lan")) {
            compose.onNodeWithTag("settings-$page").performScrollTo().performClick()
            settle()
            compose.onNodeWithTag("context-reminder").assertIsDisplayed()
            // Exactly one copy, inside the overlay; never another under the page heading.
            compose
                .onAllNodesWithText(tr(R.string.disconnect_the_vpn_before_changing_forward))
                .assertCountEquals(1)
            compose.onNodeWithTag("context-reminder").performClick()
            settle()
            compose
                .onNodeWithText(tr(R.string.disconnect_the_vpn_before_changing_forward))
                .assertIsDisplayed()
            compose.onNodeWithText(tr(R.string.close)).performClick()
            settle()
            compose
                .onNodeWithText(tr(R.string.disconnect_the_vpn_before_changing_forward))
                .assertDoesNotExist()
            compose.onNodeWithTag("settings-back").performScrollTo().performClick()
            settle()
            compose.onNodeWithTag("context-reminder").assertDoesNotExist()
        }
    }

    @Test
    fun darkColorWarningAndDeleteActionLiveInDialogsAndSavingRequiresConfirmation() {
        var saved: String? = null
        fixture(IssueNoticeCenter()) {
            SolidColorPicker("#111111", listOf("#FFFFFF", "#111111")) { color, _ -> saved = color }
        }
        compose.onNodeWithTag("solid-color-delete").assertDoesNotExist()
        compose.onNodeWithTag("solid-swatch-add").performClick()
        compose.onNodeWithTag("solid-color-dialog").assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.solid_color_low_contrast)).assertDoesNotExist()
        compose.onNodeWithTag("solid-color-save").performScrollTo().performClick()
        compose.onNodeWithTag("solid-contrast-dialog").assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.solid_color_low_contrast)).assertIsDisplayed()
        assertNull(saved)
        compose.onNodeWithTag("solid-contrast-save").performClick()
        compose.waitForIdle()
        assertEquals("#111111", saved)
        compose.onNodeWithTag("solid-color-dialog").assertDoesNotExist()
        compose.onNodeWithTag("solid-swatch-#111111").performTouchInput { longClick() }
        compose.onNodeWithTag("solid-color-dialog").assertIsDisplayed()
        compose.onNodeWithTag("solid-color-delete").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun compatibilityDetailsAndReparseAreMenuActionsNotEmbeddedProfileWarnings() {
        val profile = unsupportedProfile()
        val details = mutableStateOf(false)
        var reparses = 0
        val center = IssueNoticeCenter()
        val message = compatibilityWarning(profile, null)
        fixture(center) {
            ProfilesOverview(
                AppData(profiles = listOf(profile)),
                false,
                AppUiState(),
                ProfileActions(
                    {},
                    { command, _ ->
                        when (command) {
                            ProfileCommand.DETAILS -> details.value = true
                            ProfileCommand.COMPATIBILITY ->
                                center.inspect(
                                    IssueNotice(
                                        "fixture",
                                        tr(R.string.compatibility_warning),
                                        message,
                                        IssueStage.IMPORT,
                                        IssueSeverity.WARNING,
                                        "compatibility-warning",
                                    )
                                )
                            ProfileCommand.REPARSE -> reparses++
                            else -> Unit
                        }
                    },
                    {},
                ),
            )
            if (details.value)
                ProfileDetailsSheet(profile, false, { details.value = false }, { _, _ -> })
        }
        compose.onNodeWithTag("profile-menu-${profile.id}").performClick()
        compose.onNodeWithText(tr(R.string.ui_profile_details)).performClick()
        compose.onNodeWithText(rejectionSummary(profile.rejections)).assertDoesNotExist()
        compose.onNodeWithText(tr(R.string.reparse_local_profile)).assertDoesNotExist()
        compose.runOnUiThread { details.value = false }
        compose.onNodeWithTag("profile-menu-${profile.id}").performClick()
        compose
            .onNodeWithTag("profile-compatibility-${profile.id}")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("compatibility-warning-detail").assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.close)).performClick()
        compose.onNodeWithTag("profile-menu-${profile.id}").performClick()
        compose.onNodeWithTag("profile-reparse-${profile.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reparses) }
    }

    @Test
    fun blockedAndStaleDiagnosticsMoveToASafePopupWithoutRemovingOtherEvidence() = manualClock {
        val passed =
            Models.Step(Models.Stage.DNS, Models.State.PASSED, Models.Code.DNS_OK, 2, "IPv4", 0)
        val blocked =
            Models.Step(
                Models.Stage.POLICY,
                Models.State.BLOCKED,
                Models.Code.VPN_ACTIVE,
                0,
                "CANARY-PRIVATE-ADDRESS",
                0,
            )
        val stale =
            Models.Step(
                Models.Stage.PROXY,
                Models.State.STALE,
                Models.Code.CONFIG_CHANGED,
                0,
                "CANARY-PRIVATE-ADDRESS",
                0,
            )
        val steps = listOf(passed, blocked, stale)
        assertTrue(visibleDiagnosticSteps(steps).isEmpty())
        assertEquals(listOf(passed), visibleDiagnosticSteps(listOf(passed, blocked)))
        val detail = diagnosticContextDetail(steps)
        assertFalse(detail.contains("CANARY-"))
        assertTrue(detail.contains(Models.Code.VPN_ACTIVE.message()))
        fixture(IssueNoticeCenter()) {
            DiagnosticContextNotice("fixture-report", steps, false)
            steps.forEach { StepCard(it) }
        }
        settle()
        compose.onNodeWithText(Models.State.BLOCKED.title()).assertDoesNotExist()
        compose.onNodeWithText(Models.State.STALE.title()).assertDoesNotExist()
        compose.onNodeWithText(Models.Code.DNS_OK.message()).assertIsDisplayed()
        compose.onNodeWithTag("context-reminder").assertIsDisplayed().performClick()
        settle()
        compose.onNodeWithText(detail).assertIsDisplayed()
    }

    private fun manualClock(block: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            block()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    private fun fixture(center: IssueNoticeCenter, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(
                        LocalIssueNotices provides center,
                        LocalMotionAllowed provides false,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            content()
                            IssueNoticeHost(center)
                        }
                    }
                }
            }
        }
    }

    private fun unsupportedProfile() =
        LocalProfile(
            "notice-profile",
            "Profile",
            null,
            emptyList(),
            2,
            false,
            rejections = mapOf("protocol" to 2),
            groupsUnsupported = 1,
            definition = "{}",
        )
}
