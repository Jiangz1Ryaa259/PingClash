// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber", "LongMethod") // Bounded synthetic integration fixtures.

package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnStartRecoveryTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun successfulNodeProbeDoesNotMaskTunFailureAndImmediateRetriesRecover() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        compose.waitForIdle()
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle { model.notices.entries.clear() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ControlledServers().use { fixture ->
            val config =
                JSONObject()
                    .put("type", "socks5")
                    .put("name", "Recovery fixture")
                    .put("server", "127.0.0.1")
                    .put("port", fixture.socks.localPort)
                    .put("username", "fixture-user")
                    .put("password", "fixture-private-password")
                    .toString()
            val profile = repo.importProfile("Recovery fixture", config)
            repo.select(profile.nodes.single().id)
            // Test-only boundary fault: emulate invalid runtime data without adding
            // a production fault switch or relying on OEM handling of missing packages.
            val storage =
                ProfileRepository::class.java.getDeclaredField("mutableData").apply {
                    isAccessible = true
                }
            @Suppress("UNCHECKED_CAST")
            val state = storage.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<AppData>
            try {
                val reply =
                    JSONObject(
                        Mobile.probe(
                            config,
                            "http://127.0.0.1:${fixture.http.localPort}/",
                            UUID.randomUUID().toString(),
                            4000,
                        )
                    )
                assertEquals("proxy_request_verified", reply.optString("code"))
                repo.clientSettings(
                    before.client.copy(appMode = "all", packages = emptyList()),
                    "127.0.0.1:${fixture.dns.localPort}",
                    before.probeUrl,
                )
                state.value = state.value.copy(dnsAddress = "0.0.0.0")
                // System VPN consent needs an Activity-result caller. A raw startActivity
                // has no callingPackage and the platform dismisses its consent Activity.
                compose.onNodeWithTag("nav-0").performClick()
                compose.onNodeWithTag("vpn-power").performClick()
                approveSyntheticVpn(device, compose)
                repeat(2) {
                    VpnRuntime.connect(app)
                    try {
                        compose.waitUntil(15000) { VpnRuntime.state.value.phase == "error" }
                    } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                        device.dumpWindowHierarchy(
                            java.io.File(app.getExternalFilesDir(null), "recovery-timeout.xml")
                        )
                        val state = VpnRuntime.state.value
                        throw AssertionError(
                            "phase=${state.phase}; code=${state.failureCode}; " +
                                "message=${state.message}",
                            failure,
                        )
                    }
                    assertEquals("dns_invalid", VpnRuntime.state.value.failureCode)
                    assertFalse(VpnRuntime.state.value.active)
                    assertFalse(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
                    compose.waitUntil(5000) {
                        compose.onNodeWithTag("vpn-error-badge").isDisplayed()
                    }
                    compose.onNodeWithTag("vpn-error-badge").assertIsDisplayed().performClick()
                    compose.onNodeWithTag("vpn-error-detail").assertIsDisplayed()
                    compose.onNodeWithText(tr(R.string.close)).performClick()
                    assertFalse(VpnRuntime.state.value.message.contains("fixture-private"))
                    // No sleep: retry immediately at the terminal-state boundary.
                }
                repo.clientSettings(
                    before.client.copy(appMode = "all", packages = emptyList()),
                    "127.0.0.1:${fixture.dns.localPort}",
                    before.probeUrl,
                )
                compose.onNodeWithTag("vpn-power").performClick()
                compose.waitUntil(20000) {
                    VpnRuntime.state.value.phase in setOf("established", "error")
                }
                assertEquals(
                    VpnRuntime.state.value.message,
                    "established",
                    VpnRuntime.state.value.phase,
                )
                assertNull(VpnRuntime.state.value.failureCode)
                assertTrue(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
            } finally {
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                repo.delete(profile.id)
                repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
            }
        }
    }
}
