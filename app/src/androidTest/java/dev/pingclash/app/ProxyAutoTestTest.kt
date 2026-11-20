// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening Proxies measures the profile without a button, an automatic group then names the node it
 * would use, and back leaves nested groups one level at a time.
 */
@RunWith(AndroidJUnit4::class)
class ProxyAutoTestTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun shows(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun node(name: String, port: Int) =
        JSONObject()
            .put("name", name)
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", port)
            .put("username", "fixture-user")
            .put("password", "fixture-private-password")

    private fun group(name: String, type: String, members: List<String>) =
        JSONObject().put("name", name).put("type", type).put("proxies", JSONArray(members)).apply {
            if (type != "select") put("url", "http://192.0.2.123:8080/").put("interval", 300)
        }

    private val names = listOf("Alive A", "Alive B", "Closed")

    /** Main holds an automatic group, a manual group and the nodes; Closed refuses. */
    private fun subscription(alive: Int, closed: Int) =
        JSONObject()
            .put(
                "proxies",
                JSONArray()
                    .put(node("Alive A", alive))
                    .put(node("Alive B", alive))
                    .put(node("Closed", closed)),
            )
            .put(
                "proxy-groups",
                JSONArray()
                    .put(group("Main", "select", listOf("Auto", "Hand") + names))
                    .put(group("Auto", "url-test", names))
                    .put(group("Hand", "select", names)),
            )
            .toString()

    @Test
    fun latencyAppearsByItselfAndBackGoesOneLevel() {
        val before = app.repository.data.value
        val closed = java.net.ServerSocket(0).use { it.localPort }
        ControlledServers().use { fixture ->
            val profile =
                app.repository.importProfile(
                    "Auto test fixture",
                    subscription(fixture.socks.localPort, closed),
                )
            ProxyAutoTest.enabled = true
            try {
                app.repository.activate(profile.id)
                app.repository.select(profile.groupId("Main"))
                app.repository.clientSettings(
                    before.client.copy(
                        probeTimeout = 3000,
                        unifiedDelay = false,
                        delayUrl = "http://192.0.2.123:8080/",
                    ),
                    before.dnsAddress,
                    before.probeUrl,
                )
                compose.waitForIdle()
                compose.onNodeWithTag("nav-1").performClick()
                checkMeasuredByItself(profile)
                checkBackOneLevel()
            } finally {
                ProxyAutoTest.enabled = false
                model.cancelProxyTests()
                app.repository.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                app.repository.delete(profile.id)
            }
        }
    }

    private fun checkMeasuredByItself(profile: LocalProfile) {
        // No button pressed: every node gets a result on its own.
        compose.waitUntil(30_000) {
            val tests = model.proxyTests.value
            !tests.running &&
                names.all {
                    tests.delayFor(profile.id, it)?.state in setOf("passed", "failed", "timeout")
                }
        }
        assertEquals("failed", model.proxyTests.value.delayFor(profile.id, "Closed")?.state)
        // Main uses Auto, which names a working node, not a count of answers.
        compose.waitUntil(5_000) {
            shows("route-stop-Auto") &&
                compose
                    .onAllNodes(
                        hasAnyAncestor(hasTestTag("route-exit")) and
                            (hasText("Alive A") or hasText("Alive B"))
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    private fun checkBackOneLevel() {
        compose.openProxyGroup("Main")
        // A nested group's row has no details panel; only its arrow opens it.
        compose.onNodeWithTag("group-choice-Main-Hand").performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(!shows("proxy-member-details"))
        compose.onNodeWithTag("proxy-open-Hand").performClick()
        compose.waitUntil(5_000) { shows("group-members-Hand") }
        // The nested group is a sheet of its own, over the one it was opened from.
        assertEquals(2, compose.onAllNodesWithTag("group-sheet").fetchSemanticsNodes().size)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.waitUntil(5_000) { shows("group-members-Main") && !shows("group-members-Hand") }
        device.pressBack()
        compose.waitUntil(5_000) { !shows("group-sheet") }
        compose.onNodeWithTag("proxy-page").assertIsDisplayed()
        assertTrue(compose.activity.hasWindowFocus())
    }
}
