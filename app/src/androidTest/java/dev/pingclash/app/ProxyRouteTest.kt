// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The route follows the subscription's own groups, and help appears only on trouble. */
@RunWith(AndroidJUnit4::class)
class ProxyRouteTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val pool = null

    @Test
    fun theRouteFollowsNestedGroupsToTheExit() {
        // Manual group inside the main group: its pick is the exit.
        val manual = routeProfile(mainPick = ROUTE_MANUAL)
        val route = proxyRoute(routeData(manual), manual, GroupLive.EMPTY, pool)
        assertEquals(listOf(ROUTE_MAIN, ROUTE_MANUAL), route.stops.map { it.group.name })
        assertEquals("香港 01", route.exit)
        // An automatic group: unknown with the VPN off, the core's pick while connected.
        val auto = routeProfile()
        val off = proxyRoute(routeData(auto), auto, GroupLive.EMPTY, pool)
        assertEquals(listOf(ROUTE_AUTO, null), off.stops.map { it.pick })
        assertNull(off.exit)
        val live = GroupLive(now = mapOf(ROUTE_AUTO to "日本 01"))
        assertEquals("日本 01", proxyRoute(routeData(auto), auto, live, pool).exit)
        // A pin wins over the core's own pick.
        val pinned = routeProfile(autoPin = "香港 02")
        assertEquals("香港 02", proxyRoute(routeData(pinned), pinned, live, pool).exit)
        // A node chosen directly in the main group.
        val direct = routeProfile(mainPick = "日本 01")
        val route2 = proxyRoute(routeData(direct), direct, GroupLive.EMPTY, pool)
        assertEquals(listOf(ROUTE_MAIN), route2.stops.map { it.group.name })
        assertEquals("日本 01", route2.exit)
    }

    @Test
    fun aCycleOrMissingMainProxyEndsTheRouteSafely() {
        val looped =
            routeProfile().let { p ->
                p.copy(
                    groups =
                        p.groups.map {
                            if (it.name == ROUTE_MANUAL)
                                it.copy(proxies = listOf(ROUTE_MAIN), selected = ROUTE_MAIN)
                            else it
                        }
                )
            }
        val data =
            routeData(
                looped.copy(
                    groups =
                        looped.groups.map {
                            if (it.name == ROUTE_MAIN) it.copy(selected = ROUTE_MANUAL) else it
                        }
                )
            )
        val route = proxyRoute(data, data.profiles.single(), GroupLive.EMPTY, pool)
        assertEquals(listOf(ROUTE_MAIN, ROUTE_MANUAL), route.stops.map { it.group.name })
        assertNull(route.exit)
        assertEquals(
            ProxyRoute(emptyList(), null),
            proxyRoute(AppData(), looped, GroupLive.EMPTY, pool),
        )
    }

    @Test
    fun latencyComesFromTestsThenTheCoreAndReadsAsAColour() {
        val live = GroupLive(delays = mapOf("香港 01" to 120, "日本 01" to 0))
        val tests = ProxyTestState()
        assertEquals(ProxyDelay("passed", 120), memberDelay(tests, "p", "香港 01", "g", live))
        assertEquals("failed", memberDelay(tests, "p", "日本 01", "g", live)?.state)
        val own =
            tests.copy(values = mapOf(delayKey("p", "香港 01", "g") to ProxyDelay("passed", 80)))
        assertEquals(ProxyDelay("passed", 80), memberDelay(own, "p", "香港 01", "g", live))
        assertEquals(LatencyTone.GOOD, latencyTone(ProxyDelay("passed", 120)))
        assertEquals(LatencyTone.SLOW, latencyTone(ProxyDelay("passed", 450)))
        assertEquals(LatencyTone.BAD, latencyTone(ProxyDelay("timeout")))
        assertEquals(LatencyTone.UNKNOWN, latencyTone(null))
        assertEquals(
            "日本 01",
            routeProfile().exitOf(ROUTE_AUTO, GroupLive(now = mapOf(ROUTE_AUTO to "日本 01"))),
        )
        assertNull(routeProfile().exitOf(ROUTE_AUTO, GroupLive.EMPTY))
    }

    @Test
    fun helpAppearsOnlyWhenTheExitFails() {
        val profile = routeProfile(mainPick = ROUTE_MANUAL)
        val route = proxyRoute(routeData(profile), profile, GroupLive.EMPTY, pool)
        val history = mapOf("香港 02" to listOf<Long>(90, 95, 92))
        fun help(delay: ProxyDelay?) =
            routeHelp(route, delay) { _, name -> history[name].orEmpty() }
        assertNull(help(ProxyDelay("passed", 100)))
        assertNull(help(null))
        assertEquals(
            RouteHelp.Switch(profile.groups.single { it.name == ROUTE_MANUAL }, "香港 02"),
            help(ProxyDelay("timeout")),
        )
        // No record to judge by: the connection doctor.
        assertEquals(
            RouteHelp.Doctor,
            routeHelp(route, ProxyDelay("failed")) { _, _ -> emptyList() },
        )
        // An automatic group moves on by itself; it is never pinned for the user.
        val auto = routeProfile(autoPin = "香港 01")
        val autoRoute = proxyRoute(routeData(auto), auto, GroupLive.EMPTY, pool)
        assertEquals(
            RouteHelp.Doctor,
            routeHelp(autoRoute, ProxyDelay("failed")) { _, n -> history[n].orEmpty() },
        )
    }

    @Test
    fun theCardAndSheetRowsAct() {
        val profile = routeProfile(mainPick = ROUTE_MANUAL)
        val state =
            routeState(
                profile,
                ProxyTestState(
                    values =
                        mapOf(delayKey(profile.id, "香港 01", ROUTE_MANUAL) to ProxyDelay("timeout")),
                    history =
                        mapOf(delayKey(profile.id, "香港 02", ROUTE_MANUAL) to listOf(90, 95, 92)),
                ),
            )
        var helped: RouteHelp? = null
        var chosen = ""
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    Column {
                        RouteCard(
                            state,
                            proxyRoute(state.data, profile, state.live, null),
                            routeActions(help = { helped = it }),
                        )
                        MemberRow(
                            state,
                            profile.groups.single { it.name == ROUTE_AUTO },
                            "日本 01",
                            routeActions(choose = { g, n -> chosen = "${g.name}/$n" }),
                        )
                        AutomaticRow(
                            state,
                            profile.groups.single { it.name == ROUTE_AUTO },
                            routeActions(choose = { g, n -> chosen = "${g.name}/$n" }),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("route-stop-$ROUTE_MAIN").assertExists()
        compose.onNodeWithTag("route-stop-$ROUTE_MANUAL").assertExists()
        compose.onNodeWithText(tr(R.string.proxy_route_exit_failed)).assertExists()
        compose.onNodeWithTag("route-help-action").performClick()
        compose.runOnIdle {
            assertEquals(
                RouteHelp.Switch(profile.groups.single { it.name == ROUTE_MANUAL }, "香港 02"),
                helped,
            )
        }
        compose.onNodeWithTag("group-choice-$ROUTE_AUTO-日本 01").performClick()
        compose.runOnIdle { assertEquals("$ROUTE_AUTO/日本 01", chosen) }
        compose.onNodeWithTag("group-auto-$ROUTE_AUTO").performClick()
        compose.runOnIdle { assertEquals("$ROUTE_AUTO/", chosen) }
    }
}
