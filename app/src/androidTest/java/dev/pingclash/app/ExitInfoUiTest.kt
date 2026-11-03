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
