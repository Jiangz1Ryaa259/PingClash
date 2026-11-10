// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlexibleProxyTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun socks(name: String, port: Int) =
        JSONObject()
            .put("name", name)
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", port)
            .put("username", "fixture-user")
            .put("password", "fixture-private-password")

    private fun peer(command: String) {
        app.startActivity(
            Intent()
                .setComponent(
                    ComponentName("dev.pingclash.app.test", "dev.pingclash.test.PeerVpnActivity")
                )
                .putExtra("command", command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun approvePeer() {
        if (!device.wait(Until.hasObject(By.pkg("com.android.vpndialogs")), 3000)) return
        device.findObject(By.clazz("android.widget.CheckBox"))?.let {
            if (!it.isChecked) it.click()
        }
        val button = device.wait(Until.findObject(By.res("android", "button1")), 3000)
        assertNotNull(button)
        button!!.click()
    }

    private fun approveOwn() {
        val permission = VpnService.prepare(app) ?: return
        compose.runOnUiThread { compose.activity.startActivityForResult(permission, 12040) }
        approveSyntheticVpn(device, compose)
    }

    @Test
    fun nativeNodeDelayUsesAnExistingSeparateUidVpnInsteadOfRejectingIt() {
        val repo = app.repository
        val before = repo.data.value
        val manager = app.getSystemService(ConnectivityManager::class.java)
        ControlledServers().use { fixture ->
            val profile =
                repo.importProfile(
                    "Foreign VPN fixture",
                    socks("Foreign path", fixture.socks.localPort).toString(),
                )
            try {
                repo.activate(profile.id)
                repo.clientSettings(
                    before.client.copy(
                        unifiedDelay = false,
                        delayUrl = "http://127.0.0.1:${fixture.http.localPort}/",
                    ),
                    before.dnsAddress,
                    before.probeUrl,
                )
                compose.waitForIdle()
                assertNull(PingVpnService.instance)
                peer("start")
                approvePeer()
                compose.waitUntil(10000) {
                    manager
                        .getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                val platform = AndroidCorePlatform(app)
                assertNull(platform.underlying())
                assertEquals(manager.activeNetwork, platform.outbound())
                model.testProxies(profile, listOf("Foreign path"))
                compose.waitUntil(10000) { !model.proxyTests.value.running }
                assertEquals(
                    "passed",
                    model.proxyTests.value.delayFor(profile.id, "Foreign path")?.state,
                )
                assertTrue(fixture.handshakes.get() >= 1)
                assertFalse(VpnRuntime.state.value.active)
            } finally {
                model.cancelProxyTests()
                peer("stop")
                compose.waitUntil(10000) {
                    manager
                        .getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
                }
                repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                repo.delete(profile.id)
            }
        }
    }

    @Test
    fun tappingAnotherMemberKeepsIndependentBatchRunningAndItsResults() {
        val repo = app.repository
        val before = repo.data.value
        val hanging = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val acceptor =
            thread(isDaemon = true) {
                while (!hanging.isClosed) runCatching { hanging.accept().also { sockets.add(it) } }
            }
        ControlledServers().use { fixture ->
            val slow =
                JSONObject()
                    .put("name", "Slow")
                    .put("type", "anytls")
                    .put("server", "127.0.0.1")
                    .put("port", hanging.localPort)
                    .put("password", "queue-fixture")
            val raw =
                JSONObject()
                    .put(
                        "proxies",
                        JSONArray().put(slow).put(socks("Fast", fixture.socks.localPort)),
                    )
                    .put(
                        "proxy-groups",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", "Main")
                                    .put("type", "select")
                                    .put("proxies", JSONArray(listOf("Slow", "Fast")))
                            ),
                    )
                    .toString()
            val profile = repo.importProfile("Selectable batch", raw)
            try {
                repo.activate(profile.id)
                repo.clientSettings(
                    before.client.copy(
                        probeConcurrency = 1,
                        probeTimeout = 4000,
                        unifiedDelay = false,
                        delayUrl = "http://127.0.0.1:${fixture.http.localPort}/",
                    ),
                    before.dnsAddress,
                    before.probeUrl,
                )
                compose.onNodeWithTag("nav-1").performClick()
                compose.onNodeWithTag("proxy-test-group").performClick()
                compose.waitUntil(5000) { sockets.isNotEmpty() && model.proxyTests.value.running }
                val nodeIds = repo.data.value.activeProfile!!.nodes.map { it.id }
                compose.onNodeWithTag("group-choice-Main-Fast").assertIsEnabled().performClick()
                compose.waitUntil(5000) {
                    repo.data.value.activeProfile?.groups?.first()?.selected == "Fast"
                }
                assertTrue(
                    "Selection cancelled an independent batch",
                    model.proxyTests.value.running,
                )
                assertEquals(nodeIds, repo.data.value.activeProfile!!.nodes.map { it.id })
                compose.waitUntil(12000) { !model.proxyTests.value.running }
                assertEquals(2, model.proxyTests.value.completed)
                assertEquals(
                    "passed",
                    model.proxyTests.value.delayFor(profile.id, "Fast", "Main")?.state,
                )
                assertEquals("Fast", repo.data.value.activeProfile!!.groups.first().selected)
            } finally {
                model.cancelProxyTests()
                hanging.close()
                sockets.forEach { it.close() }
                acceptor.join(1000)
                repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                repo.delete(profile.id)
            }
        }
    }

    @Test
    fun selectingNodeWhileVpnRunsReusesTunAndActuallyChangesOutbound() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WebsiteTlsFixture().use { tls ->
            ControlledServers().use { first ->
                ControlledServers(mapOf(("192.0.2.123" to 443) to tls.port)).use { second ->
                    checkLiveSelection(tls, first, second, scope)
                }
            }
        }
        scope.cancel()
    }

    private fun checkLiveSelection(
        tls: WebsiteTlsFixture,
        first: ControlledServers,
        second: ControlledServers,
        scope: CoroutineScope,
    ) {
        val repo = app.repository
        val before = repo.data.value
        val raw =
            JSONObject()
                .put(
                    "proxies",
                    JSONArray()
                        .put(socks("VPN A", first.socks.localPort))
                        .put(socks("VPN B", second.socks.localPort)),
                )
                .toString()
        val profile = repo.importProfile("Live selection fixture", raw)
        val checks = ServiceChecks(scope, app)
        try {
            repo.activate(profile.id)
            repo.clientSettings(
                before.client.copy(mode = "global"),
                before.dnsAddress,
                before.probeUrl,
            )
            approveOwn()
            VpnRuntime.connect(app)
            compose.waitUntil(10000) {
                VpnRuntime.state.value.phase in setOf("established", "error")
            }
            assertEquals("established", VpnRuntime.state.value.phase)
            val manager = app.getSystemService(ConnectivityManager::class.java)
            compose.waitUntil(10000) {
                manager
                    .getNetworkCapabilities(manager.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            val service = PingVpnService.instance!!
            val field =
                PingVpnService::class.java.getDeclaredField("descriptor").apply {
                    isAccessible = true
                }
            val tun = field.get(service)
            val start = VpnRuntime.state.value.sessionStartedElapsed
            compose.onNodeWithTag("nav-1").performClick()
            val group = profile.displayGroups().first().name
            compose.onNodeWithTag("group-choice-$group-VPN B").assertIsEnabled().performClick()
            // Check at once, while the switch is still being applied: the check must wait for
            // the new route instead of meeting the swap.
            compose.waitUntil(15000) { repo.data.value.selected?.name == "VPN B" }
            val target = ServiceTarget("fixture", "Fixture", "https://192.0.2.123/")
            checks.start(3000, listOf("fixture"), listOf(target))
            compose.waitUntil(15000) {
                !model.ui.value.busy && VpnRuntime.state.value.phase == "established"
            }
            assertSame(tun, field.get(service))
            assertEquals(start, VpnRuntime.state.value.sessionStartedElapsed)
            compose.waitUntil(10000) { !checks.state.value.running }
            assertTrue(
                "${checks.state.value}; first=${first.intercepted.get()}; " +
                    "second=${second.intercepted.get()}; tls=${tls.handshakes.get()}",
                checks.state.value.values["fixture"] is Latency.Measured,
            )
            assertTrue(second.intercepted.get() >= 1)
            compose.waitUntil(3000) { tls.handshakes.get() >= 1 }
            assertTrue(
                "A TLS handshake alone must not count as website success",
                tls.requests.get() >= 1 && "GET" in tls.methods,
            )
            assertEquals(0, first.intercepted.get())
        } finally {
            checks.close()
            VpnRuntime.disconnect(app)
            compose.waitUntil(10000) { !VpnRuntime.state.value.active }
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
            repo.delete(profile.id)
        }
    }
}
