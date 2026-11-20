// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Main group "节点选择" holds an automatic group, a manual group and three nodes. */
internal const val ROUTE_MAIN = "节点选择"
internal const val ROUTE_AUTO = "自动选择"
internal const val ROUTE_MANUAL = "香港节点"

internal fun routeProfile(
    mainPick: String = ROUTE_AUTO,
    autoPin: String = "",
    manualPick: String = "香港 01",
): LocalProfile {
    fun node(name: String, type: String) = LocalNode("n-$name", name, type, "{}")
    val nodes = listOf(node("香港 01", "trojan"), node("香港 02", "vmess"), node("日本 01", "ss"))
    val names = nodes.map { it.name }
    return LocalProfile(
        "route-fixture",
        "Route fixture",
        null,
        nodes,
        0,
        false,
        groups =
            listOf(
                LocalGroup(
                    ROUTE_MAIN,
                    "select",
                    listOf(ROUTE_AUTO, ROUTE_MANUAL) + names,
                    mainPick,
                    true,
                ),
                LocalGroup(ROUTE_AUTO, "url-test", names, autoPin, true),
                LocalGroup(ROUTE_MANUAL, "select", names.take(2), manualPick, true),
                LocalGroup("广告拦截", "select", listOf("REJECT", "DIRECT"), "REJECT", true),
            ),
    )
}

internal fun routeData(profile: LocalProfile = routeProfile()) =
    AppData(
        profiles = listOf(profile),
        selectedId = profile.groupId(ROUTE_MAIN),
        activeProfileId = profile.id,
    )

internal fun routeState(
    profile: LocalProfile = routeProfile(),
    tests: ProxyTestState = ProxyTestState(),
    live: GroupLive = GroupLive.EMPTY,
) = ProxyPageState(profile, routeData(profile), tests, live, locked = false)

internal fun routeActions(
    choose: (LocalGroup, String) -> Unit = { _, _ -> },
    details: (LocalGroup, String) -> Unit = { _, _ -> },
    test: (LocalGroup, List<String>) -> Unit = { _, _ -> },
    help: (RouteHelp) -> Unit = {},
) = ProxyPageActions({}, choose, test, details, help)

private fun AndroidComposeTestRule<*, *>.shows(tag: String) =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

/**
 * Opens a group's members as a user would: its stop on the route card, or its row in the list
 * below. A sheet already open for another group is closed first.
 */
internal fun AndroidComposeTestRule<*, *>.openProxyGroup(name: String) {
    if (shows("group-members-$name")) return
    if (shows("group-sheet")) closeProxyGroup()
    waitUntil(10_000) { shows("route-stop-$name") || shows("proxy-groups") }
    if (shows("route-stop-$name")) onNodeWithTag("route-stop-$name").performClick()
    else {
        onNodeWithTag("proxy-groups").performScrollToKey(name)
        onNodeWithTag("group-tab-$name").performClick()
    }
    waitUntil(5_000) { shows("group-members-$name") }
}

internal fun AndroidComposeTestRule<*, *>.closeProxyGroup() {
    // A first back may only hide the keyboard of the search field.
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    repeat(3) {
        if (shows("group-sheet")) {
            device.pressBack()
            waitForIdle()
        }
    }
    waitUntil(5_000) { !shows("group-sheet") }
}
