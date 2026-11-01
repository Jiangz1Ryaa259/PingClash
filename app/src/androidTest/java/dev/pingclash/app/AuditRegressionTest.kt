// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditRegressionTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDeltaKeepsUnsavedTextAndConcurrentAppearance() {
        val before = ClientSettings()
        val draft = before.copy(dnsHttps = "https://example.invalid/dns-query", showTraffic = false)
        val theme = before.copy(theme = "dark", scene = "clouds")
        val rebased = mergeSettingsDelta(before, draft, theme)
        assertEquals("dark", rebased.theme)
        assertEquals("clouds", rebased.scene)
        assertEquals(draft.dnsHttps, rebased.dnsHttps)
        assertFalse(rebased.showTraffic)
        val immediate = mergeSettingsDelta(before, draft, theme, deferredSettingFields)
        assertEquals(before.dnsHttps, immediate.dnsHttps)
        assertFalse(immediate.showTraffic)
    }

    @Test
    fun liveAppearanceChangesPersistWithoutRevertingNetworkSettings() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        try {
            VpnRuntime.publish(VpnUiState("established"))
            repo.patchSettings(
                before.client,
                before.client.copy(notificationSpeed = false, showTraffic = false),
            )
            val saved = ProfileRepository(app).data.value.client
            assertFalse(saved.notificationSpeed)
            assertFalse(saved.showTraffic)
            assertEquals(before.client.mode, saved.mode)
            assertTrue(runCatching { repo.patchSettings(saved, saved.copy(mtu = 1400)) }.isFailure)
        } finally {
            VpnRuntime.publish(VpnUiState())
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        }
    }

    @Test
    fun themePickerOmitsSystemChipAndKeepsManualToggle() {
        var changes = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) { ThemePicker("system") { changes++ } }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("theme-toggle").performClick()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test
    fun ruleDataPreparationIsSharedAndImportedDnsPreferenceRoundTrips() {
        val app = compose.activity.application as PingClashApplication
        assertSame(RuleData.prepare(app), RuleData.prepare(app))
        assertTrue(RuleData.prepare(app).get(15, java.util.concurrent.TimeUnit.SECONDS))
        val settings = ClientSettings(useSubscriptionDns = false)
        assertFalse(ClientSettings.read(settings.json()).useSubscriptionDns)
        assertFalse(ClientSettings().showMemory)
    }

    @Test
    fun hotNodeAndModeUpdatesKeepDescriptorTimerAndOneUsageSession() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        ControlledServers().use { fixture ->
            val node =
                JSONObject()
                    .put("type", "socks5")
                    .put("name", "A")
                    .put("server", "127.0.0.1")
                    .put("port", fixture.socks.localPort)
                    .put("username", "fixture-user")
                    .put("password", "fixture-private-password")
            val second = JSONObject(node.toString()).put("name", "B")
            val input =
                JSONObject()
                    .put("proxies", org.json.JSONArray().put(node).put(second))
                    .put(
                        "proxy-groups",
                        org.json
                            .JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", "Main")
                                    .put("type", "select")
                                    .put("proxies", org.json.JSONArray().put("A").put("B"))
                            )
                            .put(
                                JSONObject()
                                    .put("name", "Child")
                                    .put("type", "select")
                                    .put("proxies", org.json.JSONArray().put("A").put("B"))
                            ),
                    )
                    .put("rules", org.json.JSONArray().put("MATCH,Main"))
                    .toString()
            val profile = repo.importProfile("Hot routing fixture", input)
            var secondProfile: LocalProfile? = null
            try {
                repo.select(profile.nodes.first { it.name == "A" }.id)
                repo.clientSettings(
                    before.client.copy(
                        mode = "global",
                        appMode = "all",
                        packages = emptyList(),
                        keepUsage = true,
                    ),
                    "127.0.0.1:${fixture.dns.localPort}",
                    before.probeUrl,
                )
                val usage = repo.data.value.usage.sessions
                compose.onNodeWithTag("nav-0").performClick()
                compose.onNodeWithTag("vpn-power").performClick()
                approveSyntheticVpn(device, compose)
                compose.waitUntil(15000) {
                    VpnRuntime.state.value.phase in setOf("established", "error")
                }
                assertEquals("established", VpnRuntime.state.value.phase)
                val owner = requireNotNull(PingVpnService.instance)
                val descriptor =
                    PingVpnService::class
                        .java
                        .getDeclaredField("descriptor")
                        .apply { isAccessible = true }
                        .get(owner)
                val started = VpnRuntime.state.value.sessionStartedElapsed
                val primary = repo.data.value.selectedId
                model.toggleGroupMember(profile.id, "Child", "B")
                compose.waitUntil(10000) { !model.ui.value.busy }
                assertEquals(
                    "Child group edit replaced main selection",
                    primary,
                    repo.data.value.selectedId,
                )
                for (mode in listOf("direct", "rule", "global")) {
                    model.routeMode(mode)
                    compose.waitUntil(10000) { !model.ui.value.busy }
                    // Wait for the serialized service command, not a UI-only preference change.
                    compose.waitUntil(10000) {
                        JSONObject(Mobile.vpnStatus()).optString("mode") == mode
                    }
                    assertEquals("established", VpnRuntime.state.value.phase)
                    assertEquals(started, VpnRuntime.state.value.sessionStartedElapsed)
                    assertSame(
                        descriptor,
                        PingVpnService::class
                            .java
                            .getDeclaredField("descriptor")
                            .apply { isAccessible = true }
                            .get(owner),
                    )
                    assertTrue(JSONObject(Mobile.vpnStatus()).getBoolean("running"))
                }
                val secondary =
                    repo.importProfile(
                        "Second routing fixture",
                        input.replace("MATCH,Main", "MATCH,DIRECT"),
                    )
                secondProfile = secondary
                assertTrue(secondary.activationRequired)
                compose.onNodeWithTag("nav-2").performClick()
                compose.onNodeWithTag("profile-${secondary.id}").performScrollTo().performClick()
                compose.onNodeWithTag("nav-0").performClick()
                val applied =
                    PingVpnService::class.java.getDeclaredField("appliedData").apply {
                        isAccessible = true
                    }
                compose.waitUntil(10000) {
                    (applied.get(owner) as? AppData)?.activeProfileId == secondary.id
                }
                assertEquals(started, VpnRuntime.state.value.sessionStartedElapsed)
                assertSame(
                    descriptor,
                    PingVpnService::class
                        .java
                        .getDeclaredField("descriptor")
                        .apply { isAccessible = true }
                        .get(owner),
                )
                assertEquals("established", VpnRuntime.state.value.phase)
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                assertEquals(
                    "Hot updates created extra usage sessions",
                    usage + 1,
                    repo.data.value.usage.sessions,
                )
            } finally {
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                secondProfile?.let { repo.delete(it.id) }
                repo.delete(profile.id)
                repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                before.selectedId?.let { id -> runCatching { repo.select(id) } }
            }
        }
    }
}
