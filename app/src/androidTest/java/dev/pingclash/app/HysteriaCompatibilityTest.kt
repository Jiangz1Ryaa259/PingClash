package dev.pingclash.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HysteriaCompatibilityTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun selfSignedHysteriaKeepsSubscriptionPolicyAndForwardsPeerDnsAndTcp() {
        val arguments = InstrumentationRegistry.getArguments()
        val hyPort =
            requireNotNull(arguments.getString("hy2Port")) {
                    "Run tools/verify-android.ps1 for controlled host fixture"
                }
                .toInt()
        val httpPort = requireNotNull(arguments.getString("hy2HttpPort")).toInt()
        val dnsPort = requireNotNull(arguments.getString("hy2DnsPort")).toInt()
        val host = requireNotNull(arguments.getString("hy2Host"))
        val app = compose.activity.application as PingClashApplication
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val before = app.repository.data.value
        val config =
            JSONObject()
                .put("type", "hysteria2")
                .put("name", "HY2 自签回归")
                .put("server", "10.0.2.2")
                .put("port", hyPort)
                .put("password", "public-hy2-fixture-password")
                .put("sni", "fixture.invalid")
                .put("skip-cert-verify", false)
        val target = "http://127.0.0.1:$httpPort/"
        val strict =
            JSONObject(Mobile.probe(config.toString(), target, UUID.randomUUID().toString(), 5000))
        assertEquals("proxy_certificate_failed", strict.getString("code"))
        config.put("skip-cert-verify", true)
        val group =
            JSONObject()
                .put("name", "主代理回归")
                .put("type", "select")
                .put("proxies", JSONArray(listOf("HY2 自签回归", "REJECT")))
        val profile =
            app.repository.importProfile(
                "HY2 兼容回归",
                JSONObject()
                    .put("proxies", JSONArray().put(config))
                    .put("proxy-groups", JSONArray().put(group))
                    // This loopback resolver lives on the desktop fixture, behind HY2.
                    .put(
                        "dns",
                        JSONObject()
                            .put("enable", true)
                            .put("nameserver", JSONArray(listOf("tcp://127.0.0.1:$dnsPort#主代理回归")))
                            .put("default-nameserver", JSONArray(listOf("127.0.0.1"))),
                    )
                    .toString(),
            )
        try {
            app.repository.select(profile.nodes.single().id)
            assertTrue(app.repository.data.value.selected!!.certificateVerificationSkipped)
            val proxy =
                JSONObject(
                    Mobile.probe(
                        profile.nodes.single().config,
                        target,
                        UUID.randomUUID().toString(),
                        5000,
                    )
                )
            assertEquals("proxy_request_verified", proxy.getString("code"))
            assertEquals(204, proxy.getInt("httpStatus"))
            assertTrue(ProfileRepository(app).data.value.selected!!.certificateVerificationSkipped)
            if (!app.repository.data.value.certificateNoticeAcknowledged) {
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText("节点证书校验提示").fetchSemanticsNodes().isNotEmpty()
                }
                captureSyntheticFixture(compose, device, "certificate-notice.png")
                compose.onNodeWithText("知道了，不再提醒").performClick()
                compose.waitUntil(5000) { app.repository.data.value.certificateNoticeAcknowledged }
            }
            assertTrue(ProfileRepository(app).data.value.certificateNoticeAcknowledged)
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("证书校验：按配置跳过").assertDoesNotExist()
            compose.onNodeWithText("节点证书未验证", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("group-choice-主代理回归-HY2 自签回归").assertIsDisplayed()
            captureSyntheticFixture(compose, device, "node-policy-light.png")
            app.repository.select(profile.groupId("主代理回归"))
            assertEquals("group", ProfileRepository(app).data.value.selected?.type)
            assertEquals(
                "proxy_request_verified",
                JSONObject(
                        Mobile.probe(
                            app.repository.data.value.selectedRuntimeConfig!!,
                            target,
                            UUID.randomUUID().toString(),
                            5000,
                        )
                    )
                    .getString("code"),
            )
            app.repository.clientSettings(
                before.client.copy(
                    mode = "rule",
                    mtu = 1400,
                    ipv6 = false,
                    appMode = "include",
                    packages = listOf("dev.pingclash.app.test"),
                    keepUsage = true,
                    useProviderRules = false,
                    customRules = "DOMAIN-SUFFIX,blocked-fixture.test,REJECT\nMATCH,主代理回归",
                ),
                "127.0.0.1:$dnsPort",
                before.probeUrl,
            )
            device.executeShellCommand(
                "pm grant dev.pingclash.app android.permission.POST_NOTIFICATIONS"
            )
            compose.onNodeWithTag("nav-0").performClick()
            compose.onNodeWithText("当前节点按配置跳过服务器证书验证；HTTPS 网站证书仍会验证。").assertDoesNotExist()
            compose.onNodeWithText("节点证书校验提示").assertDoesNotExist()
            compose.onNodeWithTag("vpn-power").performClick()
            approveSyntheticVpn(device, compose)
            compose.waitUntil(15000) {
                VpnRuntime.state.value.phase in listOf("established", "error")
            }
            assertEquals("established", VpnRuntime.state.value.phase)
            // Settings > Connection diagnostics > Check a domain or IP manually, while the VPN
            // carries this app: the check goes around the tunnel on the real network.
            val auxiliary =
                dev.pingclash.diagnostics
                    .DiagnosticEngine(
                        dev.pingclash.diagnostics.SocketProbe(),
                        dev.pingclash.diagnostics.CoreAdapter.NotRequested(),
                    )
                    .run(
                        dev.pingclash.diagnostics.Models.Request(
                            host,
                            httpPort,
                            false,
                            "",
                            dev.pingclash.diagnostics.Models.Family.AUTO,
                        ),
                        { AndroidNetworkBinding.open(app) },
                        dev.pingclash.diagnostics.RunControl(25_000),
                    ) {}
            assertTrue(
                "Manual check failed: ${auxiliary.steps.map { "${it.stage}:${it.state}:${it.code}" }}",
                auxiliary.steps.none {
                    it.state != dev.pingclash.diagnostics.Models.State.PASSED &&
                        it.state != dev.pingclash.diagnostics.Models.State.SKIPPED
                },
            )
            val completed = CountDownLatch(1)
            var result: Bundle? = null
            val receiver =
                object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                        result = data
                        completed.countDown()
                    }
                }
            app.startActivity(
                Intent()
                    .setComponent(
                        ComponentName("dev.pingclash.app.test", "dev.pingclash.test.PeerActivity")
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("host", host)
                    .putExtra("receiver", receiver)
            )
            assertTrue("Peer did not complete", completed.await(25, TimeUnit.SECONDS))
            assertTrue(
                "HY2 VPN peer failed at ${result?.getString("stage")}",
                result?.getBoolean("success") == true,
            )
            assertTrue(result?.getBoolean("serviceDenied") == true)
            assertTrue(result?.getBoolean("privateFileDenied") == true)
            compose.waitUntil(5000) { JSONObject(Mobile.vpnStatus()).optLong("downloaded") > 0 }
            // A short flow may finish before the first rate sample. Totals must
            // advance; the initial speed baseline deliberately does not invent a spike.
            compose.waitUntil(5000) {
                VpnRuntime.state.value.downloaded > 0 && VpnRuntime.state.value.history.isNotEmpty()
            }
            compose.waitUntil(5000) {
                Mobile.vpnConnections() == "[]"
            } // Completed peer flows leave no target history.
            VpnRuntime.disconnect(app)
            compose.waitUntil(10000) { !VpnRuntime.state.value.active }
            assertTrue(app.repository.data.value.usage.downloaded > 0)
            assertTrue(app.repository.data.value.usage.sessions > before.usage.sessions)
        } finally {
            if (VpnRuntime.state.value.active) PingVpnService.instance?.onRevoke()
            compose.waitUntil(10000) { !VpnRuntime.state.value.active }
            app.repository.delete(profile.id)
            app.repository.clientSettings(before.client, before.dnsAddress, before.probeUrl)
            before.selectedId
                ?.takeIf { id -> app.repository.data.value.nodes.any { it.id == id } }
                ?.let(app.repository::select)
        }
    }
}
