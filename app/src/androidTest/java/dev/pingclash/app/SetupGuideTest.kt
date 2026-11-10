// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupGuideTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val repo
        get() = (context as PingClashApplication).repository

    /** A first launch: no setup progress and no service choices, before the activity starts. */
    private val firstLaunch =
        object : ExternalResource() {
            override fun before() {
                context.getSharedPreferences(SetupState.PREFS, 0).edit().clear().commit()
                SetupScreen.reset()
                repo.serviceRouting(emptyMap(), BLOCKING_OFF)
            }

            override fun after() {
                repo.serviceRouting(emptyMap(), BLOCKING_OFF)
                SetupState.complete(context)
                SetupScreen.reset()
            }
        }

    @get:Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val setup = firstLaunch
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun title(text: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("setup-title").assertTextEquals(text)
    }

    /** Step content scrolls; the buttons pinned below it do not. */
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun agree() {
        // The language is chosen, never preselected; Next waits for a choice.
        title(tr(R.string.setup_language_title))
        compose.onNodeWithTag("setup-next").assertIsNotEnabled()
        tap("setup-language-zh")
        assertEquals("zh", UiStrings.preference())
        tap("setup-next")
        title(tr(R.string.setup_welcome_title))
        tap("setup-agree")
        title(tr(R.string.setup_how_title))
    }

    @Test
    fun aFirstLaunchOpensOnTheAgreementAndNothingElseUntilAgreed() {
        compose.onNodeWithTag("setup-guide").assertIsDisplayed()
        compose.onNodeWithTag("glass-navigation").assertDoesNotExist()
        compose.onNodeWithTag("setup-skip-all").assertDoesNotExist()
        assertEquals(0, SetupState(context).agreement)
        agree()
        assertEquals(AGREEMENT_VERSION, SetupState(context).agreement)
        assertFalse(SetupState(context).guideDone)
    }

    @Test
    fun everyStepSavesOnlyWhenConfirmed() {
        agree()
        tap("setup-next")
        title(tr(R.string.setup_profile_title))
        tap("setup-skip")
        title(tr(R.string.setup_routing_title))
        // Skip leaves the choices alone.
        tap("route-telegram-proxy")
        tap("setup-skip")
        assertTrue(repo.data.value.client.serviceRoutes.isEmpty())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        title(tr(R.string.setup_routing_title))
        // One row may set several services.
        tap("route-google_youtube-proxy")
        tap("route-x-direct")
        tap("setup-next")
        assertEquals(
            mapOf("google" to ROUTE_PROXY, "x" to ROUTE_DIRECT, "youtube" to ROUTE_PROXY),
            repo.data.value.client.serviceRoutes,
        )
        title(tr(R.string.setup_blocking_title))
        tap("blocking-ads")
        tap("setup-next")
        assertEquals(BLOCKING_ADS, repo.data.value.client.blocking)
        title(tr(R.string.setup_ready_title))
        compose.onNodeWithTag("setup-connect").assertDoesNotExist()
        tap("setup-home")
        compose.onNodeWithTag("glass-navigation").assertIsDisplayed()
        assertTrue(SetupState(context).guideDone)
        assertFalse(SetupState(context).takeHint())
    }

    @Test
    fun skipSetupShowsWhereToFindItAndSettingsRunsItAgain() {
        agree()
        val notices =
            androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].notices
        val hint = tr(R.string.setup_skip_hint)
        // A reminder lasts five seconds, and idling the home page's animations advances the
        // test clock past it: look for it straight after the tap.
        compose.onNodeWithTag("setup-skip-all").performClick()
        compose.waitUntil(5000) { notices.entries.any { it.detail == hint } }
        compose.onNodeWithTag("glass-navigation").assertIsDisplayed()
        assertEquals("", SetupState(context).view(hasProfiles = false))
        compose.onNodeWithTag("nav-3").performClick()
        // The row may sit under the floating navigation: click it, not the point.
        compose
            .onNodeWithTag("settings-setup")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        // Again from the step after the agreement.
        title(tr(R.string.setup_how_title))
        tap("setup-skip-all")
        compose.onNodeWithTag("glass-navigation").assertIsDisplayed()
    }

    /** The language step offers every translation, and choosing one takes effect at once. */
    @Test
    fun everyLanguageIsOfferedAndJapaneseAppliesAtOnce() {
        title(tr(R.string.setup_language_title))
        UI_LANGUAGES.forEach {
            compose.onNodeWithTag("setup-language-${it.code}").assertTextEquals(it.name)
        }
        tap("setup-language-ja")
        compose.waitUntil(10_000) { UiStrings.preference() == "ja" }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("次へ").fetchSemanticsNodes().isNotEmpty()
        }
        tap("setup-next")
        title("PingClashへようこそ")
        assertTrue(tr(R.string.agreement_text).contains("本規約は英語版を正本とします"))
    }

    /** Each language opens a guide that is really in the APK, in that language. */
    @Test
    fun everyLanguageHasItsGuideInTheApp() {
        val titles =
            mapOf(
                "zh" to "PingClash 使用指南",
                "en" to "PingClash user guide",
                "ja" to "PingClash 使い方ガイド",
            )
        UI_LANGUAGES.forEach { language ->
            val guide =
                context.assets.open(guideAsset(language.code)).bufferedReader().use {
                    it.readLine()
                }
            assertEquals("# ${titles[language.code]}", guide)
        }
    }

    /** On Android 13+ a language change recreates the activity; the guide must stay closed. */
    @Test
    fun changingTheLanguageAfterSetupKeepsTheApp() {
        agree()
        tap("setup-skip-all")
        compose.onNodeWithTag("glass-navigation").assertIsDisplayed()
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        model.appearance("en", repo.data.value.client.theme)
        compose.waitUntil(10_000) { UiStrings.preference() == "en" }
        // Below Android 13 nothing recreates the activity; do it here so both paths are covered.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("glass-navigation").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("setup-guide").assertDoesNotExist()
        // The guide still opens from Settings after the recreation.
        compose.onNodeWithTag("nav-3").performClick()
        compose
            .onNodeWithTag("settings-setup")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        title(tr(R.string.setup_how_title))
        // A guide opened from Settings is still open after a recreation, at the same step.
        tap("setup-next")
        title(tr(R.string.setup_profile_title))
        compose.activityRule.scenario.recreate()
        title(tr(R.string.setup_profile_title))
    }

    @Test
    fun theCurrentStepSurvivesRecreation() {
        agree()
        tap("setup-next")
        tap("setup-skip")
        title(tr(R.string.setup_routing_title))
        tap("route-netflix-direct")
        compose.activityRule.scenario.recreate()
        title(tr(R.string.setup_routing_title))
        // The unconfirmed choice is still the draft, not saved.
        assertTrue(repo.data.value.client.serviceRoutes.isEmpty())
        tap("setup-next")
        assertEquals(mapOf("netflix" to ROUTE_DIRECT), repo.data.value.client.serviceRoutes)
    }

    @Test
    fun upgradesAndChangedAgreementsAskOnlyForTheAgreement() {
        val state = SetupState(context)
        val prefs = context.getSharedPreferences(SetupState.PREFS, 0)
        assertEquals(SetupNeed.GUIDE, state.need(hasProfiles = false))
        // An install from before the guide: profiles exist, nothing recorded.
        assertEquals(SetupNeed.AGREEMENT, state.need(hasProfiles = true))
        state.agree(upgrade = true)
        assertEquals(SetupNeed.NONE, state.need(hasProfiles = true))
        assertTrue(state.featurePending)
        state.dismissFeature()
        assertFalse(state.featurePending)
        // A later agreement version: the guide is done, only the agreement comes back.
        prefs.edit().putInt("agreement", AGREEMENT_VERSION - 1).commit()
        assertEquals(SetupNeed.AGREEMENT, state.need(hasProfiles = false))
        state.agree(upgrade = false)
        assertFalse(state.featurePending)
        assertEquals(SetupNeed.NONE, state.need(hasProfiles = false))
    }

    @Test
    fun theNewFeatureCardOpensServiceRouting() {
        agree()
        tap("setup-skip-all")
        context
            .getSharedPreferences(SetupState.PREFS, 0)
            .edit()
            .putBoolean("routing_card", true)
            .commit()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("routing-feature-card").fetchSemanticsNodes().isNotEmpty()
        }
        tap("routing-feature-open")
        compose
            .onNodeWithTag("settings-detail-title")
            .assertTextEquals(tr(R.string.service_routing))
        assertFalse(SetupState(context).featurePending)
        compose.onNodeWithText(tr(R.string.routing_regions)).assertIsDisplayed()
    }
}
