package dev.pingclash.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.diagnostics.RunControl
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionFeaturesTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    @Test
    fun udpCardsShowNotApplicableBeforeAnyTcpTest() {
        val before = app.repository.data.value
        val profile =
            app.repository.importProfile(
                "Synthetic UDP",
                "proxies: [{name: UDP fixture, type: hysteria2, server: example.invalid, " +
                    "port: 443, password: synthetic-password}]",
            )
        try {
            app.repository.activate(profile.id)
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("UDP fixture").performTouchInput { longClick() }
            compose.onNodeWithText("N/A (UDP)").assertIsDisplayed()
        } finally {
            app.repository.delete(profile.id)
            before.activeProfile?.let { app.repository.activate(it.id) }
            before.selectedId?.let { app.repository.select(it) }
        }
    }

    @Test
    fun customUaQuotaRedirectAndEncryptedPersistence() {
        SubscriptionFixture().use { fixture ->
            fixture.quotaHeader = "upload=1024; download=2048; total=8192; expire=2000000000"
            val response =
                SubscriptionDownloader.fetchResponse(fixture.source, "mihomo/synthetic-fixture")
            assertTrue(fixture.captured.size >= 2)
            assertTrue(fixture.captured.all { it["user-agent"] == "mihomo/synthetic-fixture" })
            assertEquals(3072L, response.quota!!.used)
            assertEquals(5120L, response.quota.remaining)
            val profile =
                app.repository.importWithProviders(
                    "Synthetic quota",
                    response.content,
                    fixture.source,
                    userAgent = "mihomo/synthetic-fixture",
                    response = response,
                )
            try {
                val restored =
                    ProfileRepository(app).data.value.profiles.single { it.id == profile.id }
                assertEquals(response.quota, restored.quota)
                assertEquals("mihomo/synthetic-fixture", restored.userAgent)
                compose.onNodeWithTag("nav-2").performClick()
                openProfileDetails(compose, profile.id)
                compose
                    .onNodeWithText("已用：", substring = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                compose
                    .onNodeWithText("有效期至：", substring = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                val bytes =
                    java.io
                        .File(app.filesDir, "profiles.enc")
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains("fixture-token"))
                assertFalse(bytes.contains("mihomo/synthetic-fixture"))
                val before = fixture.requests.get()
                assertTrue(
                    runCatching {
                            SubscriptionDownloader.fetchResponse(
                                fixture.source,
                                "invalid\r\nInjected: value",
                            )
                        }
                        .isFailure
                )
                assertEquals(before, fixture.requests.get())
                assertNull(SubscriptionQuota.parse("upload=1; upload=2; total=4"))
                assertNull(SubscriptionQuota.parse("total=-1; expire=not-a-number"))
                assertNull(SubscriptionQuota.parse("total=99999999999999999999"))
                assertNull(SubscriptionQuota.parse("total=0")!!.remaining)
                assertEquals(
                    Long.MAX_VALUE,
                    SubscriptionQuota.parse("upload=9223372036854775807; download=1")!!.used,
                )
                fixture.quotaHeader = null
                assertNull(SubscriptionDownloader.fetchResponse(fixture.source).quota)
            } finally {
                app.repository.delete(profile.id)
            }
        }
    }

    @Test
    fun httpProvidersPreserveGroupsCacheAndRetainLastGoodConfiguration() {
        SubscriptionFixture().use { fixture ->
            fixture.bodies["/nodes"] =
                "proxies: [{name: remote-HK, type: anytls, server: example.invalid, port: 443, password: synthetic-secret}]"
            fixture.bodies["/rules"] = "payload: ['+.example.org']"
            val text =
                """
                proxy-providers:
                  remote: {type: http, url: '${fixture.address("/nodes")}', interval: 60, path: ../../never-write.yaml}
                rule-providers:
                  websites: {type: http, url: '${fixture.address("/rules")}', interval: 60, behavior: domain}
                proxy-groups:
                  - {name: main, type: select, proxies: [auto, DIRECT]}
                  - {name: auto, type: url-test, use: [remote], interval: 60}
                rules: ['RULE-SET,websites,main', 'MATCH,main']
            """
                    .trimIndent()
            var profile = app.repository.importWithProviders("Synthetic providers", text)
            try {
                assertEquals(listOf("main", "auto"), profile.groups.map { it.name })
                assertEquals(listOf("auto", "DIRECT"), profile.groups[0].proxies)
                assertEquals(listOf("remote-HK"), profile.groups[1].proxies)
                assertEquals(listOf("RULE-SET,websites,main", "MATCH,main"), profile.rules)
                val persisted =
                    ProfileRepository(app).data.value.profiles.single { it.id == profile.id }
                assertEquals(profile.remote, persisted.remote)
                val before = fixture.requests.get()
                val cached = RemoteProfiles.load(text, profile.userAgent, profile.remote)
                assertEquals(before, fixture.requests.get())
                assertFalse(cached.content.contains("never-write"))
                assertFalse(RemoteProfiles.due(profile.remote!!))
                assertTrue(
                    RemoteProfiles.due(profile.remote!!, java.time.Instant.now().epochSecond + 61)
                )
                app.repository.activate(profile.id)
                val permission = android.net.VpnService.prepare(app)
                if (permission != null) {
                    compose.runOnUiThread { compose.activity.startActivity(permission) }
                    approveSyntheticVpn(
                        androidx.test.uiautomator.UiDevice.getInstance(
                            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        ),
                        compose,
                    )
                }
                VpnRuntime.connect(app)
                compose.waitUntil(15000) {
                    VpnRuntime.state.value.phase in listOf("established", "error")
                }
                assertEquals(
                    VpnRuntime.state.value.message,
                    "established",
                    VpnRuntime.state.value.phase,
                )
                fixture.bodies["/nodes"] =
                    fixture.bodies.getValue("/nodes").replace("remote-HK", "remote-JP")
                app.repository.refreshProviders(profile, true)
                val pending = app.repository.data.value.profiles.single { it.id == profile.id }
                assertTrue(pending.remote!!.pending)
                assertEquals(profile.definition, pending.definition)
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                VpnRuntime.connect(app)
                compose.waitUntil(15000) {
                    VpnRuntime.state.value.phase in listOf("established", "error")
                }
                assertEquals(
                    VpnRuntime.state.value.message,
                    "established",
                    VpnRuntime.state.value.phase,
                )
                profile = app.repository.data.value.profiles.single { it.id == profile.id }
                assertEquals(listOf("remote-JP"), profile.groups[1].proxies)
                assertFalse(profile.remote!!.pending)
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                fixture.statuses["/nodes"] = 503
                val failure =
                    runCatching { app.repository.refreshProviders(profile, true) }.exceptionOrNull()
                assertTrue(failure is ProviderFailure)
                assertFalse((failure as ProviderFailure).publicMessage().contains("127.0.0.1"))
                val kept = app.repository.data.value.profiles.single { it.id == profile.id }
                assertEquals(profile.definition, kept.definition)
                assertEquals(profile.remote!!.bodies, kept.remote!!.bodies)
                assertTrue(kept.remote!!.error.startsWith("1:proxy:"))
                fixture.statuses.remove("/nodes")
                fixture.bodies["/nodes"] =
                    fixture.bodies.getValue("/nodes").replace("remote-JP", "remote-TW")
                app.repository.refreshProviders(kept, true)
                profile = app.repository.data.value.profiles.single { it.id == profile.id }
                assertEquals(listOf("remote-TW"), profile.groups[1].proxies)
                assertEquals("", profile.remote!!.error)
                val disk =
                    java.io
                        .File(app.filesDir, "profiles.enc")
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                assertFalse(disk.contains("synthetic-secret"))
                assertFalse(disk.contains("never-write"))
            } finally {
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                app.repository.delete(profile.id)
            }
        }
    }

    @Test
    fun directTcpMetricHasNoProxyHandshakeAndSkipsUdp() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val accepted =
                    executor.submit<Int> {
                        server.accept().use { socket ->
                            socket.soTimeout = 2000
                            socket.getInputStream().read()
                        }
                    }
                val config =
                    JSONObject()
                        .put("type", "anytls")
                        .put("server", "127.0.0.1")
                        .put("port", server.localPort)
                        .put("password", "synthetic-secret")
                        .toString()
                val node = LocalNode("tcp-fixture", "Synthetic TCP", "anytls", config)
                RunControl(3000).use { control ->
                    assertEquals("passed", TcpLatencyProbe.run(app, node, 2000, control).state)
                }
                assertEquals(
                    "TCP metric sent an application handshake",
                    -1,
                    accepted.get(3, java.util.concurrent.TimeUnit.SECONDS),
                )
                RunControl(3000).use {
                    assertEquals(
                        "skipped",
                        TcpLatencyProbe.run(app, node.copy(type = "hysteria2"), 2000, it).state,
                    )
                }
                RunControl(3000).use {
                    it.cancel()
                    assertEquals("idle", TcpLatencyProbe.run(app, node, 2000, it).state)
                }
                val closedPort = ServerSocket(0).use { it.localPort }
                RunControl(3000).use {
                    assertEquals(
                        "failed",
                        TcpLatencyProbe.run(
                                app,
                                node.copy(
                                    config = JSONObject(config).put("port", closedPort).toString()
                                ),
                                2000,
                                it,
                            )
                            .state,
                    )
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
