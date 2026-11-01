// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyDelayPlanTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test
    fun overlappingAutomaticGroupsMeasureEachLeafOnceAndPopulateNestedViews() {
        val before = app.repository.data.value
        ControlledServers().use { fixture ->
            val names = listOf("fast-a", "fast-b", "fast-c")
            val nodes = JSONArray(names.map { socks(it, fixture.socks.localPort) })
            val profile =
                app.repository.importProfile(
                    "Synthetic overlapping groups",
                    definition(nodes, names),
                )
            try {
                configure(profile, before, 1000)
                val original = profile.definition
                assertEquals(0, fixture.intercepted.get())
                model.testProxies(profile, listOf("auto", "fallback", "fast-a"), "main")
                compose.waitUntil(10000) {
                    !model.proxyTests.value.running && model.proxyTests.value.completed == 3
                }
                val result = model.proxyTests.value
                assertEquals(3, result.total)
                assertEquals(
                    "Each unique node should issue exactly one native HEAD",
                    3,
                    fixture.intercepted.get(),
                )
                assertGroupResults(result, profile, names)
                assertEquals(original, app.repository.data.value.activeProfile?.definition)
                assertEquals(
                    listOf("auto", "fallback", "fast-a"),
                    profile.groups.first { it.name == "main" }.proxies,
                )
            } finally {
                restore(profile, before)
            }
        }
    }

    @Test
    fun groupCardsCannotDelayTheDirectFastNodeAndCancellationStopsAllWorkers() {
        val before = app.repository.data.value
        ControlledServers().use { fixture ->
            HangingEndpoint().use { slow ->
                val names = (0 until 12).map { "slow-$it" } + "fast-a"
                val nodes =
                    JSONArray(
                        names.map { name ->
                            if (name == "fast-a") socks(name, fixture.socks.localPort)
                            else
                                JSONObject()
                                    .put("name", name)
                                    .put("type", "anytls")
                                    .put("server", "127.0.0.1")
                                    .put("port", slow.server.localPort)
                                    .put("password", "synthetic-batch-password")
                        }
                    )
                val profile =
                    app.repository.importProfile("Synthetic slow group", definition(nodes, names))
                try {
                    configure(profile, before, 4000)
                    model.testProxies(profile, listOf("auto", "fallback", "fast-a"), "main")
                    compose.waitUntil(2500) {
                        model.proxyTests.value.delayFor(profile.id, "fast-a", "main")?.state ==
                            "passed"
                    }
                    assertEquals(13, model.proxyTests.value.total)
                    assertTrue(
                        "Whole group was awaited before the fast leaf",
                        model.proxyTests.value.completed < 13,
                    )
                    assertEquals(1, fixture.intercepted.get())
                    assertTrue(slow.peak.get() <= 10)
                    model.cancelProxyTests()
                    compose.waitUntil(10000) {
                        !model.proxyTests.value.running &&
                            slow.active.get() == 0 &&
                            model.proxyTests.value.values.values.none {
                                it.state in setOf("running", "queued", "members_running")
                            }
                    }
                    assertFalse(model.proxyTests.value.running)
                } finally {
                    restore(profile, before)
                }
            }
        }
    }

    @Test
    fun planBoundsCyclesAndRetainsRealChainDefinitions() {
        val plain = LocalNode("one", "plain", "socks5", "{}")
        val chained = LocalNode("two", "chained", "vless", "{}", requiresProfile = true)
        val groups =
            listOf(
                LocalGroup("root", "select", listOf("cycle", "chained", "plain"), "plain", true),
                LocalGroup("cycle", "select", listOf("root"), "root", true),
            )
        val profile =
            LocalProfile(
                "synthetic-plan",
                "Synthetic",
                null,
                listOf(plain, chained),
                0,
                false,
                groups = groups,
            )
        val plan = ProxyDelayPlan(profile, listOf("root", "plain", "root"), "main")
        assertEquals(listOf("plain"), plan.independent)
        assertEquals(listOf("chained"), plan.chained)
        assertEquals(2, plan.names.size)
        assertEquals(groups, profile.groups)
        assertEquals(
            "members",
            plan.summaryValues(emptyMap(), false)[delayKey(profile.id, "root", "main")]?.state,
        )
    }

    private fun assertGroupResults(
        result: ProxyTestState,
        profile: LocalProfile,
        names: List<String>,
    ) {
        for (group in listOf("auto", "fallback")) {
            val summary = requireNotNull(result.delayFor(profile.id, group, "main"))
            assertEquals("members", summary.state)
            assertEquals(3, summary.total)
            assertEquals(3, summary.passed)
            for (name in names) assertEquals(
                "passed",
                result.delayFor(profile.id, name, group)?.state,
            )
        }
    }

    private fun configure(profile: LocalProfile, before: AppData, timeout: Int) {
        app.repository.activate(profile.id)
        app.repository.clientSettings(
            before.client.copy(
                probeConcurrency = 10,
                probeTimeout = timeout,
                unifiedDelay = false,
                delayUrl = "http://192.0.2.123:8080/",
            ),
            before.dnsAddress,
            before.probeUrl,
        )
        compose.waitForIdle()
    }

    private fun restore(profile: LocalProfile, before: AppData) {
        model.cancelProxyTests()
        app.repository.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        app.repository.delete(profile.id)
    }

    private fun socks(name: String, port: Int) =
        JSONObject()
            .put("name", name)
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", port)
            .put("username", "fixture-user")
            .put("password", "fixture-private-password")

    private fun definition(nodes: JSONArray, members: List<String>): String {
        fun group(name: String, type: String, values: List<String>) =
            JSONObject()
                .put("name", name)
                .put("type", type)
                .put("proxies", JSONArray(values))
                .put("url", "http://192.0.2.123:8080/")
        return JSONObject()
            .put("proxies", nodes)
            .put(
                "proxy-groups",
                JSONArray()
                    .put(group("main", "select", listOf("auto", "fallback", "fast-a")))
                    .put(group("auto", "url-test", members))
                    .put(group("fallback", "fallback", members)),
            )
            .toString()
    }
}

internal class HangingEndpoint : java.io.Closeable {
    val server = ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
    val active = AtomicInteger()
    val peak = AtomicInteger()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val acceptor =
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                thread(isDaemon = true) {
                    try {
                        val buffer = ByteArray(4096)
                        while (socket.getInputStream().read(buffer) >= 0) {
                            if (server.isClosed) break
                        }
                    } catch (_: Exception) {
                        /* Cancellation closes the synthetic peer. */
                    } finally {
                        active.decrementAndGet()
                        socket.close()
                        sockets.remove(socket)
                    }
                }
            }
        }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptor.join(2000)
    }
}
