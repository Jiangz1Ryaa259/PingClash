// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExitInfoUiTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val data = AppData(selectedId = "main")
    private val connected = VpnUiState("established", sessionStartedElapsed = 5)
    private val viaNode =
        ExitInfo(
            exitRoute(connected, data)!!,
            "203.0.113.7",
            "HK",
            4760,
            "HKT Limited",
            "Proxies",
            "HK 01",
            "Trojan",
            0L,
        )

    private fun show(state: DashboardState, onRefresh: () -> Unit = {}) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(LocalUiLanguage provides "en") {
                        Column {
                            ExitLines(state)
                            ExitSection(state, onRefresh)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun state(vpn: VpnUiState, info: ExitInfo, app: AppData = data) =
        DashboardState(
            app,
            vpn,
            DiagnosticUiState(),
            ProxyTestState(),
            true,
            false,
            exit = ExitState(info),
        )

    /** A group pick, a service route or blocking changes where the card's request goes. */
    @Test
    fun theCardsRouteAndWebsiteResultsFollowGroupPicksServiceRoutesAndBlocking() {
        val repo = (compose.activity.application as PingClashApplication).repository
        val before = repo.data.value
        val raw =
            """
            proxies:
              - {name: node-a, type: ss, server: 198.51.100.1, port: 8388, cipher: aes-256-gcm, password: fixture}
              - {name: node-b, type: ss, server: 198.51.100.2, port: 8388, cipher: aes-256-gcm, password: fixture}
            proxy-groups:
              - {name: Pick, type: select, proxies: [node-a, node-b]}
            rules:
              - MATCH,Pick
            """
                .trimIndent()
        val profile = kotlinx.coroutines.runBlocking { repo.importProfile("Route key", raw) }
        try {
            repo.activate(profile.id)
            fun keys() = exitRoute(connected, repo.data.value) to websiteRequestKey(repo.data.value)
            val start = keys()
            repo.selectGroup(profile.id, "Pick", "node-b")
            val picked = keys()
            assertTrue("group pick", picked.first != start.first && picked.second != start.second)
            repo.serviceRouting(mapOf("telegram" to ROUTE_DIRECT), BLOCKING_OFF)
            val routed = keys()
            assertTrue(
                "service route",
                routed.first != picked.first && routed.second != picked.second,
            )
            repo.serviceRouting(mapOf("telegram" to ROUTE_DIRECT), BLOCKING_ADS)
            val blocked = keys()
            assertTrue("blocking", blocked.first != routed.first && blocked.second != routed.second)
            // Unrelated settings leave both alone, so results are not thrown away for nothing.
            assertEquals(blocked, keys())
        } finally {
            repo.serviceRouting(before.client.serviceRoutes, before.client.blocking)
            repo.delete(profile.id)
        }
    }

    /** A one-service guide row shows the same name as the Service routing page. */
    @Test
    fun guideRowsUseTheServiceNameInTheAppsLanguage() {
        val row = guideRows(ServiceRegion.TAIWAN).single { it.id == "bahamut" }
        assertEquals("巴哈姆特动画疯", row.label("zh"))
        assertEquals("Bahamut Ani-Gamer", row.label("en"))
        // Rows that cover several services keep their combined name.
        val combined = guideRows(ServiceRegion.TAIWAN).first { it.members.size > 1 }
        assertEquals(combined.name, combined.label("zh"))
    }

    @Test
    fun placesThatBelongToChinaUseOneFlagAndOneWordingInBothLanguages() {
        val expected =
            mapOf(
                "CN" to ("China" to "中国"),
                "HK" to ("China – Hong Kong" to "中国 – 香港"),
                "MO" to ("China – Macau" to "中国 – 澳门"),
                "TW" to ("China – Taiwan" to "中国 – 台湾"),
            )
        expected.forEach { (code, names) ->
            assertEquals("🇨🇳 ${names.first}", exitCountryLabel(code, "en"))
            assertEquals("🇨🇳 ${names.second}", exitCountryLabel(code, "zh"))
        }
        assertEquals("🇨🇳 中国 – マカオ", exitCountryLabel("MO", "ja"))
        assertEquals("🇨🇳 中国 – 台湾", exitCountryLabel("TW", "ja"))
        // Everywhere else keeps its own flag and the system's name.
        assertTrue(exitCountryLabel("US", "en").startsWith("🇺🇸 "))
        assertEquals("", exitCountryLabel("xx", "en"))
    }

    @Test
    fun cardShowsCountryAndNetworkAndDetailsShowTheRouteTrafficTakes() {
        var refreshes = 0
        show(state(connected, viaNode)) { refreshes++ }
        val country = exitCountryLabel("HK", "en")
        compose.onNodeWithTag("home-exit").assertExists()
        // On the card and in the details.
        compose.onAllNodesWithText(country, useUnmergedTree = true).assertCountEquals(2)
        listOf("203.0.113.7", "AS4760", "Proxies · HK 01 · Trojan").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("home-exit-refresh").performClick()
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun theCurrentAddressShowsWhileDisconnectedButNeverForAnOlderRoute() {
        val idle = VpnUiState("idle")
        val home = viaNode.copy(route = exitRoute(idle, data)!!, policy = "", node = "", type = "")
        show(state(idle, home))
        compose.onNodeWithTag("home-exit").assertExists()
        compose.onNodeWithText("203.0.113.7").assertExists()
        // No route row without the VPN.
        compose.onNodeWithText("HK 01", substring = true).assertDoesNotExist()
        // The address measured before connecting is not the one in use now.
        show(state(connected, home))
        compose.onNodeWithTag("home-exit").assertDoesNotExist()
        compose.onNodeWithText("203.0.113.7").assertDoesNotExist()
        // Neither is one from before the main proxy changed.
        show(state(connected, viaNode, data.copy(selectedId = "other")))
        compose.onNodeWithTag("home-exit").assertDoesNotExist()
        // Rules that send this traffic direct say so.
        show(state(connected, viaNode.copy(policy = "DIRECT", node = "", type = "")))
        compose.onNodeWithText(tr(R.string.direct)).assertExists()
    }
}
