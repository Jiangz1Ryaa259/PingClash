// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Logic fixed after the 2026-11-15 review, checked without a screen. */
@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {
    private fun profile(id: String, groups: List<LocalGroup> = emptyList(), definition: String = "") =
        LocalProfile(
            id,
            id,
            null,
            emptyList(),
            0,
            false,
            groups = groups,
            definition = definition,
        )

    @Test
    fun aMainProxyChosenOnAnotherProfileIsNotShown() {
        val data =
            AppData(
                profiles = listOf(profile("a"), profile("b")),
                selectedId = "b-main",
                activeProfileId = "b",
            )
        val choices =
            mapOf("k" to SelectionQueue.Choice(profileId = "a", group = "", value = "a-node"))
        assertEquals("b-main", data.withChoices(choices).selectedId)
        val own = mapOf("k" to SelectionQueue.Choice(profileId = "b", group = "", value = "b-node"))
        assertEquals("b-node", data.withChoices(own).selectedId)
    }

    @Test
    fun aProfileThatHidesEveryGroupStillShowsThem() {
        val groups =
            listOf(
                LocalGroup("one", "select", listOf("DIRECT"), "DIRECT", true),
                LocalGroup("two", "select", listOf("DIRECT"), "DIRECT", true),
            )
        val hidden =
            JSONObject()
                .put(
                    "proxy-groups",
                    org.json.JSONArray()
                        .put(JSONObject().put("name", "one").put("hidden", true))
                        .put(JSONObject().put("name", "two").put("hidden", true)),
                )
                .toString()
        assertEquals(listOf("one", "two"), profile("p", groups, hidden).displayGroups().map { it.name })
        val oneHidden =
            JSONObject()
                .put(
                    "proxy-groups",
                    org.json.JSONArray().put(JSONObject().put("name", "one").put("hidden", true)),
                )
                .toString()
        assertEquals(listOf("two"), profile("p", groups, oneHidden).displayGroups().map { it.name })
    }

    @Test
    fun hysteriaObfuscationNeedsItsPassword() {
        val node =
            NodeEntryState().apply {
                protocol = NodeProtocol.HYSTERIA2
                server = "example.com"
                password = "secret"
                obfuscated = true
            }
        assertNotNull(node.problem())
        node.obfsPassword = "obfs"
        assertNull(node.problem())
    }

    @Test
    fun webSocketAndGrpcCarryTheirOptions() {
        val ws =
            NodeEntryState().apply {
                protocol = NodeProtocol.VLESS
                server = "example.com"
                uuid = "00000000-0000-0000-0000-000000000000"
                transport = "ws"
                wsPath = "ray"
                wsHost = "cdn.example.com"
            }
        val wsNode = JSONObject(ws.definition()).getJSONArray("proxies").getJSONObject(0)
        val opts = wsNode.getJSONObject("ws-opts")
        assertEquals("/ray", opts.getString("path"))
        assertEquals("cdn.example.com", opts.getJSONObject("headers").getString("Host"))
        val grpc =
            NodeEntryState().apply {
                protocol = NodeProtocol.VMESS
                server = "example.com"
                uuid = "00000000-0000-0000-0000-000000000000"
                transport = "grpc"
                grpcService = "tunnel"
            }
        val grpcNode = JSONObject(grpc.definition()).getJSONArray("proxies").getJSONObject(0)
        assertEquals("tunnel", grpcNode.getJSONObject("grpc-opts").getString("grpc-service-name"))
    }
}
