// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportCompatibilityTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = (compose.activity.application as PingClashApplication).repository

    @Test
    fun remoteUriAndBase64ProvidersRetainProtocolExclusionAndDialer() {
        SubscriptionFixture().use { server ->
            val uri =
                "anytls://fixture@example.invalid:443#Keep\ntrojan://fixture@example.invalid:443#Exclude"
            val bodies =
                listOf(
                    uri,
                    android.util.Base64.encodeToString(
                        uri.toByteArray(),
                        android.util.Base64.NO_WRAP,
                    ),
                )
            bodies.forEachIndexed { index, body ->
                server.bodies["/uri-provider-$index"] = body
                val p =
                    repo.importWithProviders(
                        "URI provider fixture",
                        """
                    proxies: [{name: Hop, type: socks5, server: example.invalid, port: 1080}]
                    proxy-providers:
                     source: {type: http, url: '${server.address("/uri-provider-$index")}', exclude-type: Trojan, dialer-proxy: Hop}
                    proxy-groups: [{name: Official, type: select, use: [source]}]
                """
                            .trimIndent(),
                        null,
                    )
                try {
                    assertEquals(2, p.nodes.size)
                    assertEquals(0, p.unsupported)
                    assertEquals(listOf("Keep"), p.groups.single().proxies)
                    assertTrue(p.nodes.single { it.name == "Keep" }.config.contains("dialer-chain"))
                } finally {
                    repo.delete(p.id)
                }
            }
            assertEquals(2, server.requests.get())
        }
    }

    @Test
    fun elevenHysteria2NodesWithNativeAliasesAndUnusedMetadataImportWithoutIsolation() {
        val nodes = org.json.JSONArray()
        val members = org.json.JSONArray()
        repeat(11) { index ->
            members.put("HY-$index")
            nodes.put(
                JSONObject()
                    .put("name", "HY-$index")
                    .put("TYPE", "hysteria2")
                    .put("server", "example.invalid")
                    .put("port", 443)
                    .put("password", "CANARY-PASSWORD-1234")
                    .put("skip_cert_verify", true)
                    .put("client_fingerprint", "chrome")
                    .put("up", "100 Mbps")
                    .put("down", 200)
                    .put("template-metadata", "CANARY-UNUSED-1234")
            )
        }
        val raw =
            JSONObject()
                .put("proxies", nodes)
                .put(
                    "proxy-groups",
                    org.json
                        .JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "Official")
                                .put("type", "select")
                                .put("proxies", members)
                        ),
                )
                .toString()
        val p = repo.importProfile("Hysteria2 alias fixture", raw)
        try {
            assertEquals(11, p.nodes.size)
            assertEquals(0, p.unsupported)
            assertEquals(0, p.groupsUnsupported)
            assertEquals(11, p.groups.single().proxies.size)
            assertEquals(0, p.groups.single().unavailable)
            val restored =
                ProfileRepository(compose.activity.application as PingClashApplication)
                    .data
                    .value
                    .profiles
                    .single { it.id == p.id }
            assertEquals(p.groups, restored.groups)
            restored.nodes.forEach {
                assertFalse(it.config.contains("CANARY-UNUSED"))
                assertTrue(it.certificateVerificationSkipped)
                assertEquals(
                    "passed",
                    JSONObject(Mobile.validateNode(it.config)).getString("state"),
                )
            }
        } finally {
            repo.delete(p.id)
        }
    }

    @Test
    fun nativeFieldsAnchoredTemplatesAndLookaroundsPersistWithOriginalGroups() {
        assertTrue(
            LocalNode("policy", "policy", "tuic", """{"type":"tuic","disable-sni":true}""")
                .certificateVerificationSkipped
        )
        assertFalse(
            LocalNode(
                    "header",
                    "header",
                    "http",
                    """{"type":"http","headers":{"skip-cert-verify":"true"}}""",
                )
                .certificateVerificationSkipped
        )
        val raw =
            """
            template: &transport {type: vless, server: example.invalid, port: 443, uuid: 00000000-0000-4000-8000-000000000001, tls: false, ip-version: ipv4-prefer, alpn: null}
            proxies:
             - {<<: *transport, name: HK-ready, smux: {enabled: true, protocol: smux, max-connections: 2}}
             - {<<: *transport, name: HK-expired}
             - {name: SOCKS, type: socks5, server: example.invalid, port: 1080, udp: true}
             - {name: HTTP, type: http, server: example.invalid, port: 443, tls: true}
             - {name: SSR, type: ssr, server: example.invalid, port: 443, cipher: aes-128-cfb, password: CANARY-PASSWORD-1234, protocol: auth_sha1_v4, obfs: plain}
            proxy-groups:
             - {name: Official, type: select, proxies: [Auto, SOCKS, HTTP, SSR, DIRECT]}
             - {name: Auto, type: url-test, include-all-proxies: true, filter: '^(?=.*HK)(?!.*expired)'}
            """
                .trimIndent()
        val p = repo.importProfile("Compatibility fixture", raw)
        try {
            assertEquals(5, p.nodes.size)
            assertEquals(0, p.unsupported)
            assertEquals(0, p.groupsUnsupported)
            assertEquals(listOf("Official", "Auto"), p.groups.map { it.name })
            assertEquals(listOf("HK-ready"), p.groups[1].proxies)
            val app = compose.activity.application as PingClashApplication
            val restored = ProfileRepository(app).data.value.profiles.single { it.id == p.id }
            assertEquals(p.groups, restored.groups)
            for (node in restored.nodes) {
                assertEquals(
                    "passed",
                    JSONObject(Mobile.validateNode(node.config)).getString("state"),
                )
            }
            val stored =
                java.io.File(app.filesDir, "profiles.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains("CANARY-PASSWORD-1234"))
        } finally {
            repo.delete(p.id)
        }
    }

    @Test
    fun inlineAndHttpProviderOverridesResolveWithoutFetchingInlinePayload() {
        SubscriptionFixture().use { server ->
            server.bodies["/compatibility-nodes"] =
                "proxies: [{name: HK-source, type: anytls, " +
                    "server: example.invalid, port: 443, password: CANARY-PASSWORD-1234}]"
            val raw =
                """
                proxy-providers:
                 remote:
                  type: http
                  url: '${server.address("/compatibility-nodes")}'
                  header: {Authorization: CANARY-HEADER-1234}
                  override: {additional-prefix: 'Official-', udp: true}
                 local:
                  type: inline
                  payload: [{name: Local, type: socks5, server: example.invalid, port: 1080}]
                rule-providers:
                 domains: {type: inline, behavior: domain, payload: ['+.example.org']}
                proxy-groups: [{name: Main, type: select, use: [remote,local]}]
                rules: ['RULE-SET,domains,Main', 'MATCH,Main']
            """
                    .trimIndent()
            val prepared = RemoteProfiles.load(raw, DEFAULT_SUBSCRIPTION_UA)
            assertEquals(1, server.requests.get())
            assertEquals("CANARY-HEADER-1234", server.captured.single()["authorization"])
            val p = repo.importWithProviders("Provider compatibility", raw, null)
            try {
                assertEquals(listOf("Official-HK-source", "Local"), p.groups.single().proxies)
                assertEquals(2, p.nodes.size)
                assertEquals(2, p.rules.size)
                assertEquals(1, requireNotNull(prepared.remote).bodies.size)
                assertFalse(RemoteProfiles.due(requireNotNull(prepared.remote)))
                assertFalse(p.definition.contains("CANARY-HEADER-1234"))
            } finally {
                repo.delete(p.id)
            }
        }
    }

    @Test
    fun unavailableWarningsPersistUntilInspectedAndCanReopenFromMenu() {
        val before = repo.data.value
        // Earlier negative fixtures can remain in the repository; this checks the
        // new profile's notice, not the queue of unrelated previously imported profiles.
        compose.waitForIdle()
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle {
            model.notices.entries.clear()
            model.notices.detail.value = null
        }
        val p =
            repo.importProfile(
                "Warning fixture",
                """
                proxies:
                 - {name: Good, type: socks5, server: 127.0.0.1, port: 1080}
                 - {name: Invalid, type: anytls, server: example.invalid, port: 0, password: fixture}
                proxy-groups: [{name: Main, type: select, proxies: [Good,Invalid]}]
                """
                    .trimIndent(),
            )
        try {
            repo.activate(p.id)
            compose.onNodeWithTag("nav-1").performClick()
            compose.waitUntil(5000) { compose.onNodeWithTag("compatibility-warning").isDisplayed() }
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(5100)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText(tr(R.string.member_unavailable)).assertDoesNotExist()
            compose.onNodeWithTag("compatibility-warning").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("compatibility-warning-detail").assertIsDisplayed()
            compose.onNodeWithText(tr(R.string.close)).performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("compatibility-warning-detail").assertDoesNotExist()
            compose.onNodeWithText("已隔离", substring = true).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(5100)
            compose.onNodeWithTag("compatibility-warning").assertDoesNotExist()
            compose.onNodeWithTag("proxy-menu").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithText(tr(R.string.compatibility_warning)).performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("compatibility-warning-detail").assertIsDisplayed()
            compose.onNodeWithText(tr(R.string.close)).performClick()
        } finally {
            compose.mainClock.autoAdvance = true
            repo.delete(p.id)
            before.activeProfile?.let { repo.activate(it.id) }
            before.selectedId?.let { repo.select(it) }
        }
    }
}
