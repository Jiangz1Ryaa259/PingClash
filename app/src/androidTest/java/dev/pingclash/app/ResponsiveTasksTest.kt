// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber", "LongMethod") // Bounded synthetic concurrent task journeys.

package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveTasksTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test
    fun runningBatchAllowsProfileActionsSettingsAndAppearanceChanges() {
        val repo = app.repository
        val before = repo.data.value
        ControlledServers().use { fast ->
            HangingEndpoint().use { slow ->
                val p = fixture(fast, slow, before)
                try {
                    startSlowBatch(p, slow)
                    compose.onNodeWithTag("nav-2").performClick()
                    compose.onNodeWithTag("profile-menu-${p.id}").performClick()
                    compose.onNodeWithText(tr(R.string.rename)).assertIsEnabled().performClick()
                    // Close the rename dialog; the mutation below also exercises the
                    // ViewModel gate rather than calling the repository directly.
                    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                    model.rename(p.id, "Renamed during measurement")
                    compose.waitUntil(5000) {
                        repo.data.value.profiles.any {
                            it.id == p.id && it.name == "Renamed during measurement"
                        } && !model.ui.value.busy
                    }
                    assertTrue(model.proxyTests.value.running)
                    compose.onNodeWithTag("nav-3").performClick()
                    compose.onNodeWithTag("settings-page").assertIsDisplayed()
                    model.appearance(before.client.language, "dark")
                    compose.waitUntil(5000) { repo.data.value.client.theme == "dark" }
                    assertTrue(
                        "Appearance cancelled an unrelated node batch",
                        model.proxyTests.value.running,
                    )
                    compose.onNodeWithTag("nav-0").performClick()
                    compose.onNodeWithTag("vpn-power").assertIsEnabled()
                } finally {
                    restore(p, before)
                }
            }
        }
    }

    @Test
    fun changedMeasurementSettingsAndActiveProfileCancelOldResults() {
        val repo = app.repository
        val before = repo.data.value
        ControlledServers().use { fast ->
            HangingEndpoint().use { slow ->
                val p = fixture(fast, slow, before)
                val other = repo.importProfile("Replacement fixture", socks(fast).toString())
                try {
                    startSlowBatch(p, slow)
                    model.clientSettings(
                        repo.data.value.client.copy(probeConcurrency = 3),
                        before.dnsAddress,
                        before.probeUrl,
                    )
                    compose.waitUntil(5000) {
                        repo.data.value.client.probeConcurrency == 3 && !model.ui.value.busy
                    }
                    compose.waitUntil(5000) {
                        !model.proxyTests.value.running && slow.active.get() == 0
                    }
                    assertTrue(model.proxyTests.value.values.isEmpty())
                    startSlowBatch(p, slow)
                    model.activate(other.id)
                    compose.waitUntil(5000) {
                        repo.data.value.activeProfile?.id == other.id && !model.ui.value.busy
                    }
                    compose.waitUntil(5000) {
                        !model.proxyTests.value.running && slow.active.get() == 0
                    }
                    assertTrue(
                        "Old callbacks repopulated another profile",
                        model.proxyTests.value.values.isEmpty(),
                    )
                } finally {
                    repo.delete(other.id)
                    restore(p, before)
                }
            }
        }
    }

    @Test
    fun vpnCanStartAndStopWhileNodeBatchIsRunning() {
        val before = app.repository.data.value
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ControlledServers().use { fast ->
            HangingEndpoint().use { slow ->
                val p = fixture(fast, slow, before)
                try {
                    startSlowBatch(p, slow)
                    compose.onNodeWithTag("nav-0").performClick()
                    compose.onNodeWithTag("vpn-power").assertIsEnabled().performClick()
                    approveSyntheticVpn(device, compose)
                    compose.waitUntil(20000) {
                        VpnRuntime.state.value.phase in setOf("established", "error")
                    }
                    assertEquals(
                        VpnRuntime.state.value.message,
                        "established",
                        VpnRuntime.state.value.phase,
                    )
                    assertFalse(model.proxyTests.value.running)
                    compose.waitUntil(5000) { slow.active.get() == 0 }
                    startSlowBatch(p, slow)
                    compose.onNodeWithTag("vpn-power").assertIsEnabled().performClick()
                    compose.waitUntil(10000) {
                        !VpnRuntime.state.value.active && slow.active.get() == 0
                    }
                    assertFalse(model.proxyTests.value.running)
                    assertTrue(model.proxyTests.value.values.isEmpty())
                } finally {
                    VpnRuntime.disconnect(app)
                    compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                    restore(p, before)
                }
            }
        }
    }

    private fun socks(fast: ControlledServers) =
        JSONObject()
            .put("name", "Ready")
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", fast.socks.localPort)
            .put("username", "fixture-user")
            .put("password", "fixture-private-password")

    private fun fixture(
        fast: ControlledServers,
        slow: HangingEndpoint,
        before: AppData,
    ): LocalProfile {
        val nodes = JSONArray().put(socks(fast))
        repeat(24) {
            nodes.put(
                JSONObject()
                    .put("name", "Slow $it")
                    .put("type", "anytls")
                    .put("server", "127.0.0.1")
                    .put("port", slow.server.localPort)
                    .put("password", "synthetic-delay")
            )
        }
        val p =
            app.repository.importProfile(
                "Concurrent task fixture",
                JSONObject().put("proxies", nodes).toString(),
            )
        app.repository.activate(p.id)
        app.repository.select(p.nodes.first().id)
        app.repository.clientSettings(
            before.client.copy(
                mode = "global",
                appMode = "all",
                packages = emptyList(),
                probeTimeout = 15000,
                probeConcurrency = 2,
                unifiedDelay = false,
                delayUrl = "http://192.0.2.123/",
            ),
            before.dnsAddress,
            before.probeUrl,
        )
        compose.waitForIdle()
        return p
    }

    private fun startSlowBatch(p: LocalProfile, slow: HangingEndpoint) {
        model.testProxies(p, p.nodes.drop(1).map { it.name })
        compose.waitUntil(5000) { model.proxyTests.value.running && slow.active.get() > 0 }
    }

    private fun restore(p: LocalProfile, before: AppData) {
        model.cancelProxyTests()
        app.repository.delete(p.id)
        app.repository.clientSettings(before.client, before.dnsAddress, before.probeUrl)
    }
}
