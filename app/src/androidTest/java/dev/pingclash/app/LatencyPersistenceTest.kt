// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatencyPersistenceTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun latencyResultsSurviveSwitchingPagesAndLeavingTheApp() {
        val app = compose.activity.application as PingClashApplication
        val names = listOf("香港 01", "日本 01", "美国 01")
        val raw =
            JSONObject()
                .put(
                    "proxies",
                    JSONArray(
                        names.map {
                            JSONObject()
                                .put("name", it)
                                .put("type", "trojan")
                                .put("server", "example.invalid")
                                .put("port", 443)
                                .put("password", "latency-fixture-secret")
                        }
                    ),
                )
                .put(
                    "proxy-groups",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "节点选择")
                                .put("type", "select")
                                .put("proxies", JSONArray(names))
                        ),
                )
                .toString()
        val profile = app.repository.importProfile("延迟保留", raw)
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.onNodeWithTag("nav-1").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { model.testProxies(profile, names, "节点选择") }
        compose.waitUntil(20000) {
            !model.proxyTests.value.running && model.proxyTests.value.values.size == names.size
        }
        val results = model.proxyTests.value.values
        fun intact() = model.proxyTests.value.values == results

        for (tab in listOf(0, 2, 3, 1, 0, 1)) {
            compose.onNodeWithTag("nav-$tab").performClick()
            compose.waitForIdle()
            // Home starts its own checks a few seconds after it appears.
            Thread.sleep(if (tab == 0) 4000 else 500)
            compose.waitForIdle()
            assertTrue("results lost after opening tab $tab", intact())
        }
        // Leaving the app and coming back (background refreshes run on return).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1500)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        Thread.sleep(8000)
        compose.waitForIdle()
        assertTrue("results lost after returning to the app", intact())
        assertEquals(names.size, model.proxyTests.value.values.size)
        // Connecting or disconnecting stops running tests but keeps finished results.
        compose.runOnUiThread { model.prepareVpnTransition() }
        compose.waitForIdle()
        assertTrue("results lost on a VPN transition", intact())
    }

    @Test
    fun aProfileUpdateKeepsResultsOfUnchangedNodesOnly() {
        fun node(name: String, port: Int) =
            LocalNode(
                name,
                name,
                "trojan",
                JSONObject().put("name", name).put("port", port).toString(),
            )
        fun profile(vararg nodes: LocalNode) =
            LocalProfile("p", "p", null, nodes.toList(), 0, false)
        val before = AppData(profiles = listOf(profile(node("a", 1), node("b", 1), node("c", 1))))
        val after = before.copy(profiles = listOf(profile(node("a", 1), node("b", 2))))
        assertTrue(delayStillValid(delayKey("p", "a", "节点选择"), before, after))
        assertFalse("edited node", delayStillValid(delayKey("p", "b"), before, after))
        assertFalse("removed node", delayStillValid(delayKey("p", "c"), before, after))
        // A group's own result stays with its profile.
        assertTrue(delayStillValid(delayKey("p", "节点选择"), before, after))
        // Another profile's results stay until that profile is deleted.
        val two = before.copy(profiles = before.profiles + profile().copy(id = "q"))
        assertTrue(delayStillValid(delayKey("p", "a"), two, two.copy(activeProfileId = "q")))
        assertFalse(delayStillValid(delayKey("q", "x"), two, before))
        // Different test settings make every old number incomparable.
        val slower = after.copy(client = after.client.copy(probeTimeout = 9000))
        assertFalse(delayStillValid(delayKey("p", "a"), after, slower))
    }
}
