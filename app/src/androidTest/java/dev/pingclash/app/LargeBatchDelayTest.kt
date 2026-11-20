// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.ServerSocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A subscription the size people import (400 nodes, a quarter of them refusing) is measured in one
 * core batch at the default concurrency and finishes quickly, with a result for every node.
 */
@RunWith(AndroidJUnit4::class)
class LargeBatchDelayTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test
    fun fourHundredNodesFinishWithAResultEach() {
        val before = app.repository.data.value
        val closed = ServerSocket(0).use { it.localPort }
        ControlledServers().use { fixture ->
            val nodes = JSONArray()
            repeat(400) { i ->
                nodes.put(
                    JSONObject()
                        .put("name", "Node $i")
                        .put("type", "socks5")
                        .put("server", "127.0.0.1")
                        .put("port", if (i % 4 == 3) closed else fixture.socks.localPort)
                        .put("username", "fixture-user")
                        .put("password", "fixture-private-password")
                )
            }
            val profile =
                app.repository.importProfile(
                    "Large batch fixture",
                    JSONObject().put("proxies", nodes).toString(),
                )
            try {
                app.repository.activate(profile.id)
                app.repository.clientSettings(
                    before.client.copy(
                        probeConcurrency = DEFAULT_PROBE_CONCURRENCY,
                        probeTimeout = 5000,
                        unifiedDelay = false,
                        delayUrl = "http://192.0.2.123:8080/",
                    ),
                    before.dnsAddress,
                    before.probeUrl,
                )
                compose.waitForIdle()
                val started = System.currentTimeMillis()
                model.testProxies(profile, profile.nodes.map { it.name })
                compose.waitUntil(60_000) {
                    model.proxyTests.value.let { !it.running && it.completed == 400 }
                }
                val elapsed = System.currentTimeMillis() - started
                val values =
                    profile.nodes.map { model.proxyTests.value.delayFor(profile.id, it.name) }
                assertEquals(300, values.count { it?.state == "passed" })
                assertEquals(100, values.count { it?.state == "failed" })
                // 0.2.5-beta.6 on the API 31 emulator: about 1.7 s.
                assertTrue("400 nodes took $elapsed ms", elapsed < 20_000)
            } finally {
                model.cancelProxyTests()
                app.repository.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                app.repository.delete(profile.id)
            }
        }
    }
}
