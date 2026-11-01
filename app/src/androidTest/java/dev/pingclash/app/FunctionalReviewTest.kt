// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FunctionalReviewTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectionRuleSurvivesParsingAndIsVisible() {
        val raw =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", 1)
                        .put("network", "tcp")
                        .put("route", "DIRECT")
                        .put("ruleType", "DOMAIN-SUFFIX")
                        .put("destination", "")
                        .put("seconds", 1)
                        .put("uploaded", 0)
                        .put("downloaded", 0)
                )
                .toString()
        val row = parseConnectionRows(raw).single()
        assertEquals("DOMAIN-SUFFIX", row.ruleType)
        val older = JSONArray(raw).getJSONObject(0).apply { remove("ruleType") }
        assertEquals("", parseConnectionRows(JSONArray().put(older).toString()).single().ruleType)
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) { ConnectionListItem(row) {} }
            }
        }
        compose.onNodeWithText("TCP · DOMAIN-SUFFIX · DIRECT").assertExists()
    }

    @Test
    fun homeDashboardShowsEverythingAndRuleTableSurvivesModeChanges() {
        val vpn =
            VpnUiState(
                "established",
                downloadSpeed = 300_000,
                heapBytes = 8_000_000,
                history = List(30) { SpeedSample(it * 1000L, it * 9000L) },
            )
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    HomeDashboardSheet(
                        DashboardState(
                            AppData(),
                            vpn,
                            DiagnosticUiState(),
                            ProxyTestState(),
                            true,
                            false,
                        )
                    ) {}
                }
            }
        }
        // No preferences: the chart and every metric are always shown while connected.
        compose.onNodeWithTag("traffic-chart").assertExists()
        compose.onNodeWithText(tr(R.string.memory)).assertExists()
        compose.onNodeWithText(tr(R.string.dns_queries)).assertExists()
        val data =
            AppData(client = ClientSettings(customRules = "MATCH,DIRECT", useProviderRules = false))
        val rules =
            listOf("rule", "global", "direct").map { mode ->
                JSONObject(data.runtimeOptions(data.client.copy(mode = mode)))
                    .getJSONArray("rules")
                    .toString()
            }
        assertEquals(1, rules.distinct().size)
        assertEquals("[\"MATCH,DIRECT\"]", rules.first())
    }

    @Test
    fun runningVpnAllowsExplicitProfileSelectionWithoutUnlockingOtherEdits() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        val profile = repo.importProfile("Selectable fixture", "proxies: [{name: n, type: direct}]")
        val importedData = repo.data.value
        var selected = ""
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    PingClashTheme("light", false) {
                        CompositionLocalProvider(LocalProfileSelectionLock provides false) {
                            ProfilesOverview(
                                importedData,
                                true,
                                AppUiState(),
                                ProfileActions(
                                    select = { selected = it.id },
                                    command = { _, _ -> },
                                    add = {},
                                ),
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("profile-${profile.id}").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(profile.id, selected) }
            compose.onNodeWithTag("profile-menu-${profile.id}").performClick()
            compose.onNodeWithText(tr(R.string.delete)).assertIsNotEnabled()
            VpnRuntime.publish(VpnUiState("established"))
            repo.activate(profile.id)
            assertEquals(profile.id, repo.data.value.activeProfileId)
            assertFalse(repo.data.value.activeProfile!!.activationRequired)
        } finally {
            VpnRuntime.publish(VpnUiState())
            repo.delete(profile.id)
            before.selectedId?.let { repo.select(it) }
        }
    }

    @Test
    fun defaultThemeMatchesExplicitBuiltInPalette() {
        var expected: Color? = null
        var actual: Color? = null
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    val color = MaterialTheme.colorScheme.primary
                    SideEffect { expected = color }
                }
                PingClashTheme("light") {
                    val color = MaterialTheme.colorScheme.primary
                    SideEffect { actual = color }
                }
            }
        }
        compose.runOnIdle { assertEquals(expected, actual) }
    }
}
