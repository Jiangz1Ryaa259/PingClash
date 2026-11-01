// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiDetailsRegressionTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun modeAcknowledgementStartsOneSpringAndExternalChangesNeverResubmit() {
        val mode = mutableStateOf("rule")
        var requests = 0
        fixture {
            ModeDial(
                mode.value,
                true,
                {
                    requests++
                    mode.value = it
                },
            )
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("mode-global").performClick()
            compose.mainClock.advanceTimeBy(1800)
            assertEquals(0f, angle(), .01f)
            assertEquals(1, settlements())
            assertEquals(1, requests)
            compose.runOnIdle { mode.value = "direct" }
            compose.mainClock.advanceTimeBy(1800)
            assertEquals(68f, angle(), .01f)
            assertEquals(2, settlements())
            assertEquals(1, requests)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun rejectedDragReturnsToTheActualModeAndCanBeUsedAgain() {
        var requests = 0
        fixture { ModeDial("rule", true, { requests++ }) }
        compose.mainClock.autoAdvance = false
        try {
            dragDial()
            assertTrue("No routing request was made", requests > 0)
            compose.mainClock.advanceTimeBy(1800)
            assertEquals(-68f, angle(), .01f)
            compose.onNodeWithTag("mode-direct").performClick()
            compose.mainClock.advanceTimeBy(1800)
            assertEquals(-68f, angle(), .01f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun disablingDuringADragCancelsItAndRestoresTheActualDetent() {
        val enabled = mutableStateOf(true)
        var requests = 0
        fixture { ModeDial("rule", enabled.value, { requests++ }) }
        compose.mainClock.autoAdvance = false
        try {
            dragDial(release = false)
            compose.runOnIdle { enabled.value = false }
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("mode-knob", true).performTouchInput { cancel() }
            compose.runOnIdle { enabled.value = true }
            compose.mainClock.advanceTimeBy(1800)
            assertEquals(-68f, angle(), .01f)
            assertEquals(0, requests)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun degenerateAndOrdinarySpeedCurvesHaveFiniteCoordinatesAndSafeScales() {
        val cases =
            listOf(
                emptyList(),
                listOf(Offset.Zero),
                listOf(Offset(0f, 0f), Offset(0f, 10f), Offset(0f, 0f)),
                listOf(Offset(0f, 10f), Offset(10f, 0f), Offset(20f, 7f)),
            )
        for (points in cases) {
            val coordinates = smoothPath(points).asAndroidPath().approximate(.01f)
            assertTrue(coordinates.all(Float::isFinite))
            val ys = coordinates.filterIndexed { index, _ -> index % 3 == 2 }
            assertTrue(ys.all { it in -.01f..10.01f })
        }
        assertEquals(16384L, niceScale(0))
        assertEquals(Long.MAX_VALUE, niceScale(Long.MAX_VALUE))
    }

    @Test
    fun diagnosisRoundTripRestoresSettingsScrollAndDuplicateEntriesAreAbsent() {
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-profiles").assertDoesNotExist()
        compose.onNodeWithTag("settings-groups").assertDoesNotExist()
        val diagnosis = visibleSettingsControl("tool-diagnosis")
        // Give the round trip a position to restore. Since Profiles and Proxy groups left the
        // menu it may fit a tall screen, so scroll a little ourselves instead of relying on the
        // entry starting below the fold; on a screen where it cannot scroll, 0 must survive.
        val page = compose.onNodeWithTag("settings-page")
        val range = page.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val step = minOf(range.maxValue(), 48 * compose.activity.resources.displayMetrics.density)
        if (step > 0f) page.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
        compose.waitForIdle()
        val before = settingsScroll()
        if (range.maxValue() > 0f) assertTrue("Fixture did not scroll", before > 0f)
        diagnosis.assertIsDisplayed().performClick()
        compose.onNodeWithTag("diagnosis-back").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(before, settingsScroll(), 1f)
        compose.onNodeWithTag("tool-diagnosis").assertIsDisplayed()
    }

    @Test
    fun predictiveBackDuringImmediateEncryptedWriteDoesNotAskToSave() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-network").performClick()
        try {
            // Hold the repository monitor so an immediate write cannot be
            // acknowledged before the user finishes the back gesture.
            synchronized(repo) {
                compose.onNodeWithTag("switch-${tr(R.string.auto_reconnect)}").performClick()
                // The immediate write is queued (settings writes do not set the busy flag).
                compose.waitUntil(3000) { model.settingsPending.value > 0 }
                compose.runOnUiThread {
                    val back = compose.activity.onBackPressedDispatcher
                    back.dispatchOnBackStarted(
                        BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT)
                    )
                    back.dispatchOnBackProgressed(
                        BackEventCompat(100f, 100f, .65f, BackEventCompat.EDGE_LEFT)
                    )
                }
                compose.waitForIdle()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithTag("settings-back").assertDoesNotExist()
                compose.onNodeWithText(tr(R.string.settings_unsaved_title)).assertDoesNotExist()
            }
            compose.waitUntil(10000) { model.settingsPending.value == 0 }
            assertEquals(!before.client.autoReconnect, repo.data.value.client.autoReconnect)
        } finally {
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        }
    }

    @Test
    fun unsavedTextStillRequiresConfirmationAndCanBeDiscarded() {
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-dns").performClick()
        compose
            .onNodeWithTag("field-VPN DNS")
            .performScrollTo()
            .performTextReplacement("192.0.2.53")
        hideFixtureKeyboard()
        // A focused editor may consume system Back for IME/selection state.
        // The explicit page action exercises the shared dirty-text guard.
        visibleSettingsControl("settings-back").performClick()
        compose.waitForIdle()
        compose
            .onNodeWithText(tr(R.string.settings_unsaved_title))
            .assertExists()
            .assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.settings_discard)).performClick()
        compose.onNodeWithTag("settings-back").assertDoesNotExist()
    }

    private fun visibleSettingsControl(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        val nav = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot.top
        val bottom = node.fetchSemanticsNode().boundsInRoot.bottom
        val extra = bottom - nav + 12 * compose.activity.resources.displayMetrics.density
        if (extra > 0)
            compose.onNodeWithTag("settings-page").performSemanticsAction(
                SemanticsActions.ScrollBy
            ) {
                it(0f, extra)
            }
        compose.waitForIdle()
        return node
    }

    private fun settingsScroll() =
        compose
            .onNodeWithTag("settings-page")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun angle() =
        compose.onNodeWithTag("mode-dial").fetchSemanticsNode().config[ModeDialAngle]

    private fun settlements() =
        compose.onNodeWithTag("mode-dial").fetchSemanticsNode().config[ModeDialSettlements]

    private fun dragDial(release: Boolean = true) {
        compose.onNodeWithTag("mode-knob", true).performTouchInput {
            down(center + Offset(0f, -height * .3f))
            moveTo(center + Offset(width * .3f, -height * .2f), 100)
            moveTo(center + Offset(width * .35f, height * .25f), 100)
            moveTo(center + Offset(0f, height * .35f), 100)
            if (release) up()
        }
        compose.mainClock.advanceTimeBy(80)
    }

    private fun fixture(motion: Boolean = true, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides motion,
                        LocalAllowLanyardThree provides false,
                    ) {
                        Box(Modifier.fillMaxSize().padding(24.dp)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
