// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.diagnostics.Models
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyPresentationTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun socks(name: String, port: Int) =
        JSONObject()
            .put("name", name)
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", port)
            .put("username", "fixture-user")
            .put("password", "fixture-private-password")

    private fun bounds(name: String) =
        compose.onNodeWithTag("group-choice-Main-$name").fetchSemanticsNode().boundsInRoot

    private fun capture(name: String) =
        captureSyntheticFixture(
            compose,
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            name,
        )

    @Test
    fun openingAGroupListsMembersWithoutChangingSelectionOrMeasurements() {
        val repo = app.repository
        val raw =
            JSONObject()
                .put(
                    "proxies",
                    JSONArray().put(socks("Tokyo fixture", 1)).put(socks("Singapore fixture", 2)),
                )
                .put(
                    "proxy-groups",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "Main")
                                .put("type", "select")
                                .put(
                                    "proxies",
                                    JSONArray(listOf("Tokyo fixture", "Singapore fixture")),
                                )
                        ),
                )
                .toString()
        val p = repo.importProfile("Layout fixture", raw)
        try {
            repo.activate(p.id)
            compose.onNodeWithTag("nav-1").performClick()
            val before = repo.data.value
            val measurements = model.proxyTests.value
            compose.openProxyGroup("Main")
            // One member per row, full width, in the subscription's order.
            val a = bounds("Tokyo fixture")
            val b = bounds("Singapore fixture")
            assertTrue(b.top >= a.bottom)
            assertEquals(a.width, b.width, 2f)
            compose.onNodeWithText("TCP", substring = true).assertDoesNotExist()
            capture("proxy-group-sheet.png")
            compose.closeProxyGroup()
            assertEquals(before, repo.data.value)
            assertEquals(measurements, model.proxyTests.value)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("nav-1").performClick()
            compose.openProxyGroup("Main")
            assertEquals(a.width, bounds("Tokyo fixture").width, 2f)
            assertEquals(before, repo.data.value)
        } finally {
            repo.delete(p.id)
        }
    }

    @Test
    fun longPressTcpAndDiagnosisUseInspectedNodeWithoutSelectingItOrStartingVpn() {
        val repo = app.repository
        val before = repo.data.value
        ControlledServers().use { first ->
            ControlledServers().use { second ->
                val p =
                    repo.importProfile(
                        "Inspection fixture",
                        JSONObject()
                            .put(
                                "proxies",
                                JSONArray()
                                    .put(socks("Keep current", first.socks.localPort))
                                    .put(socks("Inspect other", second.socks.localPort)),
                            )
                            .toString(),
                    )
                try {
                    repo.activate(p.id)
                    repo.select(p.nodes.first().id)
                    repo.settings(before.dnsAddress, "https://192.0.2.123/")
                    compose.onNodeWithTag("nav-1").performClick()
                    val group = p.displayGroups().first().name
                    val selected = repo.data.value.selectedId
                    compose.openProxyGroup(group)
                    compose.onNodeWithTag("group-choice-$group-Inspect other").performTouchInput {
                        longClick()
                    }
                    compose.onNodeWithTag("proxy-member-details").assertIsDisplayed()
                    compose
                        .onNodeWithText("fixture-private-password", substring = true)
                        .assertDoesNotExist()
                    compose.onNodeWithText("127.0.0.1", substring = true).assertDoesNotExist()
                    assertEquals(selected, repo.data.value.selectedId)
                    compose.onNodeWithTag("proxy-detail-tcp").performClick()
                    compose.waitUntil(7000) {
                        model.proxyTests.value.tcpValues[delayKey(p.id, "Inspect other", group)]
                            ?.state == "passed"
                    }
                    assertEquals(
                        "passed",
                        model.proxyTests.value.tcpValues[delayKey(p.id, "Inspect other", group)]
                            ?.state,
                    )
                    compose.onNodeWithTag("proxy-detail-diagnose").performClick()
                    compose.waitUntil(30000) {
                        model.doctor.state.value.let { !it.running && it.evidence != null }
                    }
                    assertEquals(p.nodes.last().id, model.diagnosis.value.nodeId)
                    assertEquals(
                        "Inspect other",
                        requireNotNull(model.doctor.state.value.evidence).nodeName,
                    )
                    compose.onNodeWithTag("doctor-cause").assertIsDisplayed()
                    assertTrue(
                        model.diagnosis.value.report!!.steps.any { it.stage == Models.Stage.PROXY }
                    )
                    assertTrue(second.handshakes.get() > 0)
                    // The selected node is reached only when the doctor compares other nodes,
                    // which it does when the inspected one fails, and then it says so.
                    val peers = model.doctor.state.value.evidence?.peers.orEmpty()
                    assertTrue(
                        peers.any { it.name == "Keep current" } || first.handshakes.get() == 0
                    )
                    val firstBefore = first.handshakes.get()
                    assertEquals(selected, repo.data.value.selectedId)
                    assertFalse(VpnRuntime.state.value.active)
                    val count = second.handshakes.get()
                    model.diagnoseData(repo.data.value.copy(selectedId = p.nodes.last().id))
                    compose.waitUntil(12000) {
                        !model.diagnosis.value.running && second.handshakes.get() > count
                    }
                    assertEquals(p.nodes.last().id, model.diagnosis.value.nodeId)
                    assertEquals(selected, repo.data.value.selectedId)
                    // Repeating the node check reaches only the inspected node.
                    assertEquals(firstBefore, first.handshakes.get())
                } finally {
                    model.doctor.cancel()
                    model.cancelDiagnosis()
                    model.cancelProxyTests()
                    compose.waitUntil(5000) { !model.diagnosis.value.running }
                    repo.settings(before.dnsAddress, before.probeUrl)
                    repo.delete(p.id)
                }
            }
        }
    }

    @Test
    fun largeFontUsesSingleColumnAndUdpTcpRemainsNotApplicableInReadOnlyDetails() {
        val nodes =
            listOf(
                LocalNode("udp", "UDP fixture", "hysteria2", "{}"),
                LocalNode("tcp", "TCP fixture", "socks5", "{}"),
            )
        val group = LocalGroup("Main", "select", nodes.map { it.name }, nodes.first().name, true)
        val p =
            LocalProfile("large-fixture", "Fixture", null, nodes, 0, false, groups = listOf(group))
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                        ProxyScreen(
                            AppData(listOf(p), selectedId = "udp"),
                            true,
                            model,
                            ProxyTestState(),
                            {},
                            {},
                        )
                    }
                }
            }
        }
        compose.openProxyGroup("Main")
        assertTrue(bounds("TCP fixture").top >= bounds("UDP fixture").bottom)
        compose.onNodeWithText("TCP：", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("group-choice-Main-UDP fixture").performTouchInput { longClick() }
        compose.onNodeWithText(tr(R.string.latency_udp_na)).assertIsDisplayed()
        compose.onNodeWithTag("proxy-detail-tcp").assertIsNotEnabled()
        compose.onNodeWithTag("proxy-detail-diagnose").assertIsNotEnabled()
        assertFalse(VpnRuntime.state.value.active)
    }
}
