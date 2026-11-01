// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBatteryTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private fun profile(quota: SubscriptionQuota) =
        LocalProfile("battery-fixture", "Quota fixture", null, emptyList(), 0, false, quota = quota)

    @Test
    fun compactCardsShowRemainingBatteryAndMenuKeepsExactQuotaAndInventory() {
        val raw = "proxies: [{name: quota-node, type: socks5, server: example.invalid, port: 443}]"
        val quota = SubscriptionQuota(1024, 2048, 8192, 2000000000)
        val p =
            app.repository.importWithProviders(
                "Quota fixture",
                raw,
                "https://example.invalid/CANARY-SUB-TOKEN-1234",
                response = SubscriptionResponse(raw, quota),
            )
        try {
            compose.onNodeWithTag("nav-2").performClick()
            val index = app.repository.data.value.profiles.indexOfFirst { it.id == p.id } + 1
            compose.onNodeWithTag("profiles-list").performScrollToIndex(index)
            // The selectable card merges its child accessibility nodes.
            compose
                .onNodeWithTag("profile-quota-${p.id}", useUnmergedTree = true)
                .assertIsDisplayed()
            compose.onNodeWithText(tr(R.string.profile_remaining_percent, 62)).assertIsDisplayed()
            val card = compose.onNodeWithTag("profile-${p.id}").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Quota card grew back to the old tall layout",
                card.height <= with(compose.density) { 120.dp.toPx() },
            )
            compose.onNodeWithText("已用：", substring = true).assertDoesNotExist()
            captureSyntheticFixture(
                compose,
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                "profile-battery-light.png",
            )
            openProfileDetails(compose, p.id)
            compose.onNodeWithText(tr(R.string.profile_inventory, 1, 0, 0)).assertIsDisplayed()
            compose.onNodeWithText("已用：", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("总配额：", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("CANARY-SUB-TOKEN-1234", substring = true).assertDoesNotExist()
        } finally {
            app.repository.delete(p.id)
        }
    }

    @Test
    fun refillOnlyAnimatesOnActualIncreaseAndRespectsReducedMotion() {
        val current = mutableStateOf(profile(SubscriptionQuota(0, 90, 100)))
        val motion = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme {
                    CompositionLocalProvider(LocalMotionAllowed provides motion.value) {
                        ProfileQuotaFill(current.value, true) {
                            Text("Quota fixture", Modifier.height(96.dp))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(100)
            fun state() =
                compose
                    .onNodeWithTag("profile-quota-battery-fixture")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.StateDescription]
            assertNotEquals(tr(R.string.profile_quota_refilling), state())
            compose.runOnUiThread { current.value = profile(SubscriptionQuota(0, 10, 100)) }
            compose.mainClock.advanceTimeBy(100)
            assertEquals(tr(R.string.profile_quota_refilling), state())
            compose.mainClock.advanceTimeBy(2000)
            assertEquals(tr(R.string.profile_remaining_percent, 90), state())
            compose.runOnUiThread { current.value = profile(SubscriptionQuota(0, 20, 100)) }
            compose.mainClock.advanceTimeBy(100)
            assertNotEquals(tr(R.string.profile_quota_refilling), state())
            compose.runOnUiThread {
                motion.value = false
                current.value = profile(SubscriptionQuota(0, 0, 100))
            }
            compose.mainClock.advanceTimeBy(100)
            assertEquals(tr(R.string.profile_remaining_percent, 100), state())
            assertNull(remainingFraction(SubscriptionQuota(0, 0, 0)))
            assertEquals(0f, remainingFraction(SubscriptionQuota(0, 200, 100))!!, 0f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun selectionGleamIsFiniteAndDoesNotRepeatOnEntryOrWithoutMotion() {
        val selected = mutableStateOf(true)
        val motion = mutableStateOf(true)
        lateinit var visual: ProfileQuotaMotion
        val p = profile(SubscriptionQuota(0, 20, 100))
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme {
                    CompositionLocalProvider(LocalMotionAllowed provides motion.value) {
                        visual = rememberProfileQuotaMotion(p, selected.value)
                        Text("Quota fixture")
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(100)
            assertFalse(visual.sweeping)
            compose.runOnUiThread { selected.value = false }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnUiThread { selected.value = true }
            compose.mainClock.advanceTimeBy(100)
            assertTrue(visual.sweeping)
            val start = visual.gleam.value
            compose.mainClock.advanceTimeBy(400)
            assertTrue(visual.gleam.value > start)
            compose.mainClock.advanceTimeBy(1500)
            assertFalse(visual.sweeping)
            compose.runOnUiThread {
                motion.value = false
                selected.value = false
            }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnUiThread { selected.value = true }
            compose.mainClock.advanceTimeBy(100)
            assertFalse(visual.sweeping)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun nativeLine394CompatibilityAndSafeCategoryReachFailureDetails() {
        val lines = JSONArray()
        repeat(393) { lines.put("DOMAIN,unmatched.invalid,DIRECT") }
        lines.put("AND,((NETWORK,tcp),(DST-PORT,443/8443)),PROXY")
        val reply =
            JSONObject(Mobile.validateRuntimeOptions(JSONObject().put("rules", lines).toString()))
        assertEquals("passed", reply.optString("state"))
        lines.put(393, "DOMAIN-SUFFIX,https://example.invalid/path,PROXY")
        assertEquals(
            "passed",
            JSONObject(Mobile.validateRuntimeOptions(JSONObject().put("rules", lines).toString()))
                .optString("state"),
        )
        lines.put(393, "DST-PORT,invalid,CANARY-PASSWORD-1234")
        val failed =
            JSONObject(Mobile.validateRuntimeOptions(JSONObject().put("rules", lines).toString()))
        assertEquals(394, failed.optInt("line"))
        val message = VpnConfigurationFailure.options(failed).publicMessage()
        assertTrue(message.contains("DST-PORT"))
        assertTrue(message.contains("394"))
        assertFalse(message.contains("CANARY-"))
        failed.put("ruleType", "CANARY-SUB-TOKEN-1234")
        assertFalse(VpnConfigurationFailure.options(failed).publicMessage().contains("CANARY-"))
        assertTrue(
            VpnConfigurationFailure.options(failed)
                .publicMessage()
                .contains(tr(R.string.rule_category_unknown))
        )
        lines.put(393, "PROCESS-NAME-REGEX,[CANARY-PASSWORD-1234,DIRECT")
        val raw =
            JSONObject()
                .put("rules", lines)
                .put("definition", "{\"proxies\":[{\"cipher\":\"auto\"}]}")
                .toString()
        val missing = JSONObject(Mobile.validateRuntimeOptions(raw)).apply { remove("ruleType") }
        val fallback = VpnConfigurationFailure.options(missing, raw).publicMessage()
        assertTrue(fallback.contains("PROCESS-NAME"))
        assertTrue(fallback.contains("394"))
        assertTrue(fallback.contains("不是配置文件行号"))
        assertFalse(fallback.contains("CANARY-"))
        assertFalse(fallback.contains("cipher"))
        settingsFailuresKeepRuleCategoryWithoutCommitting()
    }

    private fun settingsFailuresKeepRuleCategoryWithoutCommitting() {
        val repo = app.repository
        val before = repo.data.value
        val invalid =
            before.client.copy(
                mode = "rule",
                useProviderRules = false,
                customRules = "PROCESS-NAME-REGEX,[CANARY-PASSWORD-1234,DIRECT",
            )
        val failure =
            runCatching { repo.clientSettings(invalid, before.dnsAddress, before.probeUrl) }
                .exceptionOrNull()
        assertTrue(failure is SettingsFailure)
        val settings = failure as SettingsFailure
        assertEquals(IssueStage.RULE, failureStage(settings))
        assertTrue(settings.publicMessage.contains("PROCESS-NAME"))
        assertFalse(settings.publicMessage.contains("CANARY-"))
        assertEquals(before, repo.data.value)
        try {
            repo.clientSettings(invalid.copy(mode = "global"), before.dnsAddress, before.probeUrl)
            val global = repo.data.value
            val modeFailure =
                runCatching { repo.routeMode("rule") }.exceptionOrNull() as SettingsFailure
            assertTrue(modeFailure.publicMessage.contains("PROCESS-NAME"))
            assertFalse(modeFailure.publicMessage.contains("CANARY-"))
            assertEquals(global, repo.data.value)
        } finally {
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        }
    }
}
