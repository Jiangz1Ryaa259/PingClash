// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // User-visible notice lifetime and gesture regressions.

package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IssueNoticeTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val center
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java].notices

    private fun notice(severity: IssueSeverity) =
        IssueNotice(
            "notice-fixture",
            tr(
                if (severity == IssueSeverity.WARNING) R.string.issue_warning
                else R.string.issue_reminder
            ),
            tr(R.string.vpn_dns_invalid),
            IssueStage.DNS,
            severity,
        )

    private fun settlePopup() {
        repeat(2) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    @Test
    fun reminderDisappearsAfterFiveSeconds() {
        assertNotEquals("Warning and Back must use different vector IDs", WARNING_GLYPH, BACK_GLYPH)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { center.publish(notice(IssueSeverity.REMINDER)) }
            compose.mainClock.advanceTimeBy(300)
            settlePopup()
            compose.onNodeWithTag("issue-popup").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(6000)
            compose.onNodeWithTag("issue-popup").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun warningSurvivesTimeOutsideTapAndPageChangeUntilSwiped() {
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { center.publish(notice(IssueSeverity.WARNING)) }
            compose.runOnUiThread {
                center.publish(notice(IssueSeverity.REMINDER).copy(id = "queued-reminder"))
            }
            compose.mainClock.advanceTimeBy(6300)
            settlePopup()
            compose.onNodeWithTag("issue-popup").assertIsDisplayed()
            compose.onNodeWithTag("nav-2").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("profiles-list").assertIsDisplayed()
            captureSyntheticFixture(
                compose,
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                "issue-warning-light.png",
            )
            compose.onNodeWithTag("issue-popup").assertIsDisplayed().performTouchInput {
                swipeRight()
            }
            settlePopup()
            compose.onNodeWithTag("issue-popup").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(6000)
            compose.onNodeWithTag("issue-popup").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun unreadWarningSurvivesRecreationAndClickShowsLayerAndAdviceThenDismissesIt() {
        compose.runOnUiThread { center.publish(notice(IssueSeverity.WARNING)) }
        compose.onNodeWithText(tr(R.string.vpn_dns_invalid)).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("issue-popup").assertIsDisplayed().performClick()
        compose.onNodeWithTag("issue-popup").assertDoesNotExist()
        compose.onNodeWithText(tr(R.string.issue_stage_dns)).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.vpn_dns_invalid)).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.issue_advice_dns)).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.close)).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("issue-popup").assertDoesNotExist()
    }
}
