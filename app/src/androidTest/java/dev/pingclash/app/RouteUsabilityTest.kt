// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber", "LongMethod") // Synthetic full routing and UI journeys.

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteUsabilityTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun socks(name: String, port: Int) =
        JSONObject()
            .put("type", "socks5")
            .put("name", name)
            .put("server", "127.0.0.1")
            .put("port", port)
            .put("username", "fixture-user")
            .put("password", "fixture-private-password")

    @Test
    fun ruleModeStartsWithBundledGeoSiteGeoIpAndKeepsOfficialGroup() {
        val repo = app.repository
        val before = repo.data.value
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ControlledServers().use { fixture ->
            val raw =
                JSONObject()
                    .put("proxies", JSONArray().put(socks("Only fixture", fixture.socks.localPort)))
                    .put(
                        "proxy-groups",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", "Official")
                                    .put("type", "select")
                                    .put("proxies", JSONArray(listOf("Only fixture")))
                            ),
                    )
                    .put(
                        "rules",
                        JSONArray(
                            listOf(
                                "GEOSITE,google,Official",
                                "GEOIP,CN,DIRECT,no-resolve",
                                "MATCH,Official",
                            )
                        ),
                    )
            val p = repo.importProfile("Geo rule fixture", raw.toString())
            try {
                repo.activate(p.id)
                repo.clientSettings(
                    before.client.copy(mode = "rule", appMode = "all", packages = emptyList()),
                    "127.0.0.1:${fixture.dns.localPort}",
                    before.probeUrl,
                )
                compose.onNodeWithTag("nav-0").performClick()
                compose.onNodeWithTag("vpn-power").performClick()
                approveSyntheticVpn(device, compose)
                compose.waitUntil(20000) {
                    VpnRuntime.state.value.phase in setOf("established", "error")
                }
                assertEquals(
                    VpnRuntime.state.value.message,
                    "established",
                    VpnRuntime.state.value.phase,
                )
                assertTrue(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
                assertEquals("rule", repo.data.value.client.mode)
                assertEquals(
                    listOf("Official"),
                    repo.data.value.activeProfile!!.groups.map { it.name },
                )
            } finally {
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                repo.delete(p.id)
                repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
            }
        }
    }

    @Test
    fun completedMemberCanUseTcpAndDiagnosisBeforeBatchEndsAndReturnsToSameOffset() {
        // The harness's key events leave the device in key mode, where Android focuses the
        // first visible card on return and scrolls it fully into view. This checks the touch
        // path, where the offset must stay exactly as left.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val repo = app.repository
        val before = repo.data.value
        ControlledServers().use { fast ->
            HangingEndpoint().use { slow ->
                val slowNames = (0 until 30).map { "Slow $it" }
                val names = slowNames + "Ready"
                val nodes =
                    JSONArray(
                            slowNames.map {
                                JSONObject()
                                    .put("name", it)
                                    .put("type", "anytls")
                                    .put("server", "127.0.0.1")
                                    .put("port", slow.server.localPort)
                                    .put("password", "synthetic-delay")
                            }
                        )
                        .put(socks("Ready", fast.socks.localPort))
                val p =
                    repo.importProfile(
                        "Follow-up fixture",
                        JSONObject()
                            .put("proxies", nodes)
                            .put(
                                "proxy-groups",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("name", "Main")
                                            .put("type", "select")
                                            .put("proxies", JSONArray(names))
                                    ),
                            )
                            .toString(),
                    )
                try {
                    repo.activate(p.id)
                    repo.clientSettings(
                        before.client.copy(
                            mode = "global",
                            probeConcurrency = 1,
                            probeTimeout = 10000,
                            unifiedDelay = false,
                            delayUrl = "http://192.0.2.123:8080/",
                        ),
                        before.dnsAddress,
                        "https://192.0.2.123/",
                    )
                    compose.onNodeWithTag("nav-1").performClick()
                    compose.onNodeWithTag("group-members-Main").performScrollToIndex(30)
                    val tag = "group-choice-Main-Ready"
                    val top = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
                    val grid = { gridScroll(compose.onNodeWithTag("group-members-Main")) }
                    val left = grid()
                    model.testProxies(p, listOf("Ready") + slowNames, "Main")
                    compose.waitUntil(5000) {
                        model.proxyTests.value.delayFor(p.id, "Ready", "Main")?.state == "passed"
                    }
                    assertTrue(model.proxyTests.value.running)
                    compose.onNodeWithTag(tag).performTouchInput { longClick() }
                    compose.onNodeWithTag("proxy-detail-tcp").assertIsEnabled().performClick()
                    compose.waitUntil(5000) {
                        model.proxyTests.value.tcpValues[delayKey(p.id, "Ready", "Main")]?.state ==
                            "passed"
                    }
                    assertTrue("TCP waited for the batch", model.proxyTests.value.running)
                    compose.onNodeWithTag("proxy-detail-diagnose").assertIsEnabled().performClick()
                    compose.waitUntil(20000) {
                        model.diagnosis.value.report != null && !model.diagnosis.value.running
                    }
                    compose.onNodeWithTag("diagnosis-back").performClick()
                    compose.onNodeWithTag("page-title-proxies").assertIsDisplayed()
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                    assertEquals(
                        "grid scroll before $left, after ${grid()}",
                        top,
                        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top,
                        3f,
                    )
                    model.cancelProxyTests()
                    compose.onNodeWithTag("nav-2").performClick()
                    compose.onNodeWithTag("nav-1").performClick()
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                    assertEquals(
                        "grid scroll before $left, after ${grid()}",
                        top,
                        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top,
                        3f,
                    )
                    assertFalse(VpnRuntime.state.value.active)
                } finally {
                    model.cancelDiagnosis()
                    model.cancelProxyTests()
                    compose.waitUntil(10000) { !model.diagnosis.value.running }
                    repo.delete(p.id)
                    repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                }
            }
        }
    }

    @Test
    fun latencyThresholdsAndErrorBadgeKeepMessageOutOfDashboardUntilOpened() {
        assertEquals(R.string.ui_latency_fast, latencyLabel(299))
        assertEquals(R.string.ui_latency_moderate, latencyLabel(300))
        assertEquals(R.string.ui_latency_moderate, latencyLabel(600))
        assertEquals(R.string.ui_latency_high, latencyLabel(601))
        val message = VpnConfigurationFailure("rule_unsupported", 7).publicMessage()
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    assertTrue(warningColor(false).green > MaterialTheme.colorScheme.error.green)
                    DashboardScreen(
                        DashboardState(
                            AppData(),
                            VpnUiState("error", message, failureCode = "rule_unsupported"),
                            DiagnosticUiState(),
                            ProxyTestState(),
                            true,
                            false,
                        ),
                        DashboardActions({}, {}, {}, {}),
                    )
                }
            }
        }
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithTag("vpn-error-badge").assertIsDisplayed().performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.close)).performClick()
        compose.onNodeWithText(message).assertDoesNotExist()
    }
}

private fun gridScroll(node: androidx.compose.ui.test.SemanticsNodeInteraction): String {
    val semantics = node.fetchSemanticsNode()
    val range =
        semantics.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
    return "${range.value()}/${range.maxValue()} in ${semantics.boundsInRoot}"
}
