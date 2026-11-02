// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Local native-DNS/TUN integration deadlines.

package dev.pingclash.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeConfigurationTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeDNSFakeIPAndSubRulesForwardPeerUIDAndSurviveRestart() {
        val app = compose.activity.application as PingClashApplication
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previous = app.repository.data.value
        ControlledServers().use { fixture ->
            val node =
                JSONObject()
                    .put("name", "native-server")
                    .put("type", "socks5")
                    .put("server", "127.0.0.1")
                    .put("port", fixture.socks.localPort)
                    .put("username", "fixture-user")
                    .put("password", "fixture-private-password")
            val source =
                JSONObject()
                    .put("proxies", JSONArray().put(node))
                    .put(
                        "proxy-groups",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", "main")
                                    .put("type", "select")
                                    .put("proxies", JSONArray().put("native-server"))
                            ),
                    )
                    .put(
                        "dns",
                        JSONObject()
                            .put("enable", true)
                            .put("enhanced-mode", "fake-ip")
                            .put(
                                "nameserver",
                                JSONArray().put("tcp://127.0.0.1:${fixture.dns.localPort}#main"),
                            )
                            .put("listen", "0.0.0.0:15353"),
                    )
                    .put(
                        "sub-rules",
                        JSONObject()
                            .put(
                                "peer-traffic",
                                JSONArray()
                                    .put("DOMAIN-SUFFIX,fixture.test,main")
                                    .put("MATCH,REJECT"),
                            ),
                    )
                    .put(
                        "rules",
                        JSONArray().put("SUB-RULE,(NETWORK,tcp),peer-traffic").put("MATCH,main"),
                    )
                    .toString()
            val profile = app.repository.importProfile("Native configuration fixture", source)
            try {
                app.repository.select(requireNotNull(profile.defaultTargetId))
                app.repository.clientSettings(
                    previous.client.copy(mode = "rule"),
                    previous.dnsAddress,
                    previous.probeUrl,
                )
                val stored =
                    ProfileRepository(app).data.value.profiles.single { it.id == profile.id }
                assertTrue(JSONObject(stored.definition).has("dns"))
                assertTrue(JSONObject(stored.definition).has("sub-rules"))
                assertFalse(
                    File(app.filesDir, "profiles.enc")
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .contains("fixture-private-password")
                )
                repeat(2) {
                    compose.onNodeWithTag("nav-0").performClick()
                    awaitPowerControl()
                    compose.onNodeWithTag("vpn-power").assertIsEnabled().performClick()
                    approveSyntheticVpn(device, compose)
                    compose.waitUntil(20_000) {
                        VpnRuntime.state.value.phase in listOf("established", "error")
                    }
                    assertEquals(
                        VpnRuntime.state.value.message,
                        "established",
                        VpnRuntime.state.value.phase,
                    )
                    val done = CountDownLatch(1)
                    var result: Bundle? = null
                    val receiver =
                        object : ResultReceiver(Handler(Looper.getMainLooper())) {
                            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                                result = resultData
                                done.countDown()
                            }
                        }
                    val peer =
                        ComponentName("dev.pingclash.app.test", "dev.pingclash.test.PeerActivity")
                    app.startActivity(
                        Intent()
                            .setComponent(peer)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("host", fixture.host)
                            .putExtra("receiver", receiver)
                    )
                    assertTrue(done.await(25, TimeUnit.SECONDS))
                    // The peer runs in another task and UID. Android 14+ does not bring this
                    // task back when it finishes, so return explicitly, as a user would.
                    compose.runOnUiThread {
                        compose.activity.startActivity(
                            Intent(app, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                        )
                    }
                    assertTrue(
                        "Native path failed at ${result?.getString("stage")}",
                        result?.getBoolean("success") == true,
                    )
                    assertTrue(result?.getBoolean("serviceDenied") == true)
                    assertTrue(result?.getBoolean("privateFileDenied") == true)
                    VpnRuntime.disconnect(app)
                    compose.waitUntil(10_000) { !VpnRuntime.state.value.active }
                }
                // fake-IP generation avoids an upstream query, while the restored
                // hostname must actually traverse the configured SOCKS outbounds.
                assertTrue(fixture.intercepted.get() >= 2)
            } finally {
                VpnRuntime.disconnect(app)
                compose.waitUntil(10_000) { !VpnRuntime.state.value.active }
                app.repository.delete(profile.id)
                app.repository.clientSettings(
                    previous.client,
                    previous.dnsAddress,
                    previous.probeUrl,
                )
            }
        }
    }

    private fun awaitPowerControl() {
        // The peer activity returns asynchronously; wait for the resumed UI and
        // its actual enabled state before attempting the next user action.
        compose.waitUntil(15_000) {
            compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                compose
                    .onAllNodesWithTag("vpn-power")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.config
                    ?.contains(SemanticsProperties.Disabled) == false
        }
    }
}
