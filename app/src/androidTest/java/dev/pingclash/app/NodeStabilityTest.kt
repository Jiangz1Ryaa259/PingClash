// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A node is rated on its recent checks, not its last one, and the group's steadiest is marked. */
@RunWith(AndroidJUnit4::class)
class NodeStabilityTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun historyKeepsTheLastTenResultsAndIgnoresStatesThatAreNotResults() {
        var history = emptyList<Long>()
        repeat(12) { history = history.plusResult(ProxyDelay("passed", it.toLong() + 100)) }
        history = history.plusResult(ProxyDelay("timeout"))
        history = history.plusResult(ProxyDelay("busy")).plusResult(ProxyDelay("idle"))
        assertEquals(STABILITY_HISTORY, history.size)
        assertEquals(FAILED_CHECK, history.last())
        assertEquals(103L, history.first())
    }

    @Test
    fun stabilityNeedsThreeChecksAndCountsFailuresAndSpread() {
        assertNull(stabilityOf(listOf(100, 120)))
        assertEquals(
            NodeStability(checks = 4, failures = 1, typicalMs = 120, spreadMs = 13),
            stabilityOf(listOf(100, FAILED_CHECK, 120, 140)),
        )
        assertEquals(
            NodeStability(checks = 3, failures = 3, typicalMs = 0, spreadMs = 0),
            stabilityOf(listOf(FAILED_CHECK, FAILED_CHECK, FAILED_CHECK)),
        )
    }

    @Test
    fun theSteadiestNodeWinsOverTheFastestLastResult() {
        val histories =
            mapOf(
                // Fastest last check, but it failed once and swings widely.
                "Fast" to listOf<Long>(40, FAILED_CHECK, 400, 45),
                "Steady" to listOf<Long>(150, 155, 148, 152),
                "Jumpy" to listOf<Long>(90, 300, 95, 310),
                "New" to listOf<Long>(30),
            )
        assertEquals("Steady", mostStable(histories))
        // Equal failures and spread: the faster one.
        assertEquals(
            "Quick",
            mostStable(
                mapOf("Quick" to listOf<Long>(50, 50, 50), "Slow" to listOf<Long>(200, 200, 200))
            ),
        )
        // One rated node is not a choice, and a node failing most checks is never marked.
        assertNull(mostStable(mapOf("Only" to listOf<Long>(50, 50, 50), "New" to listOf<Long>(30))))
        assertNull(
            mostStable(
                mapOf(
                    "A" to listOf<Long>(FAILED_CHECK, FAILED_CHECK, 80),
                    "B" to listOf<Long>(FAILED_CHECK, FAILED_CHECK, FAILED_CHECK),
                )
            )
        )
    }

    @Test
    fun theMarkAndTheDetailLineAreShown() {
        val profile = routeProfile()
        fun key(name: String) = delayKey(profile.id, name, ROUTE_MANUAL)
        val state =
            routeState(
                profile,
                ProxyTestState(
                    history =
                        mapOf(
                            key("香港 01") to listOf(150, 155, 148),
                            key("香港 02") to listOf(90, FAILED_CHECK, 400),
                        )
                ),
            )
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    MemberRow(state, state.groups.getValue(ROUTE_MANUAL), "香港 01", routeActions())
                }
            }
        }
        compose.onNodeWithTag("proxy-stable-香港 01", useUnmergedTree = true).assertExists()
        compose
            .onNodeWithText(
                tr(R.string.proxy_most_stable),
                substring = true,
                useUnmergedTree = true,
            )
            .assertExists()
        assertEquals(
            tr(R.string.proxy_stability_value, 4, 1, 120L, 13L),
            stabilityText(stabilityOf(listOf(100, FAILED_CHECK, 120, 140))),
        )
        assertEquals(tr(R.string.proxy_stability_pending, 3), stabilityText(null))
    }
}
