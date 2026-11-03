// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Synthetic TLS fixtures and public failure categories.

package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.mobile.Mobile
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderDownloadTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val nodes =
        "proxies: [{name: CDN fixture, type: socks5, server: example.invalid, port: 443}]"

    private fun source(url: String) =
        """
        proxy-providers:
          pool:
            type: http
            url: '$url'
            header: {Authorization: CANARY-AUTH-1234, X-Api-Key: CANARY-KEY-1234, User-Agent: mihomo/provider-fixture}
        proxy-groups:
          - {name: main, type: select, use: [pool]}
        rules: ['MATCH,main']
    """
            .trimIndent()

    @Test
    fun httpsCdnRedirectLoadsGroupsWithoutForwardingCredentials() {
        SubscriptionFixture().use { first ->
            SubscriptionFixture().use { cdn ->
                first.redirects["/provider-start"] = cdn.address("/payload")
                cdn.bodies["/payload"] = nodes
                val loaded =
                    RemoteProfiles.load(
                        source(first.address("/provider-start")),
                        DEFAULT_SUBSCRIPTION_UA,
                        force = true,
                    )
                assertTrue(loaded.content.contains("CDN fixture"))
                assertTrue(loaded.content.contains("main"))
                assertEquals("CANARY-AUTH-1234", first.captured.single()["authorization"])
                assertEquals("CANARY-KEY-1234", first.captured.single()["x-api-key"])
                assertFalse(cdn.captured.single().containsKey("authorization"))
                assertFalse(cdn.captured.single().containsKey("x-api-key"))
                assertEquals("mihomo/provider-fixture", cdn.captured.single()["user-agent"])
                first.redirects["/same-origin"] = "/payload"
                first.bodies["/payload"] = nodes
                SubscriptionDownloader.fetchResponse(
                    first.address("/same-origin"),
                    headers = mapOf("Authorization" to "CANARY-AUTH-1234"),
                )
                assertEquals("CANARY-AUTH-1234", first.captured.last()["authorization"])
                val headers = mapOf("Authorization" to "CANARY-AUTH-1234")
                assertEquals(
                    headers,
                    redirectedSubscriptionHeaders(
                        headers,
                        URI("https://example.invalid"),
                        URI("https://example.invalid:443/path"),
                    ),
                )
                first.redirects["/downgrade"] = "http://example.invalid/CANARY-SUB-TOKEN-1234"
                val blocked =
                    runCatching {
                            SubscriptionDownloader.fetchResponse(first.address("/downgrade"))
                        }
                        .exceptionOrNull()
                assertTrue(blocked is SubscriptionTransferFailure)
                assertFalse(
                    (blocked as SubscriptionTransferFailure).publicMessage().contains("CANARY-")
                )
            }
        }
    }

    @Test
    fun httpStatusAndTypedFailuresPersistWithoutPrivateExceptionText() {
        SubscriptionFixture().use { fixture ->
            fixture.statuses["/denied"] = 403
            fixture.bodies["/denied"] = "CANARY-SUB-TOKEN-1234"
            val failed =
                runCatching {
                        RemoteProfiles.load(
                            source(fixture.address("/denied")),
                            DEFAULT_SUBSCRIPTION_UA,
                        )
                    }
                    .exceptionOrNull() as ProviderFailure
            assertEquals("provider_http_403", failed.code)
            assertEquals(1, failed.index)
            assertEquals("proxy", failed.kind)
            assertTrue(failed.publicMessage().contains("403"))
            assertFalse(failed.publicMessage().contains("CANARY-"))
            val remote =
                RemoteProfile("synthetic", emptyMap(), emptyMap(), error = "1:proxy:${failed.code}")
            assertEquals(remote.error, RemoteProfile.read(remote.json())!!.error)
            assertEquals("", providerErrorRecord("1:proxy:CANARY-SUB-TOKEN-1234"))
        }
        listOf(
                java.net.UnknownHostException("CANARY-SUB-TOKEN-1234") to "provider_dns",
                java.net.SocketTimeoutException("CANARY-SUB-TOKEN-1234") to "provider_timeout",
                javax.net.ssl.SSLHandshakeException("CANARY-SUB-TOKEN-1234") to "provider_tls",
                java.net.ConnectException("CANARY-SUB-TOKEN-1234") to "provider_connect",
            )
            .forEach { (failure, code) ->
                val public = providerDownloadFailure(2, "rule", failure)
                assertEquals(code, public.code)
                assertFalse(public.publicMessage().contains("CANARY-"))
            }
        val cancelled = kotlinx.coroutines.CancellationException("cancel")
        assertSame(
            cancelled,
            runCatching { providerDownloadFailure(1, "proxy", cancelled) }.exceptionOrNull(),
        )
        assertNotNull(downloadFailureText("provider_network"))
    }

    @Test
    fun largeRuleSourceImportsReloadsAndReportsSpecificFailures() {
        SubscriptionFixture().use { fixture ->
            fixture.bodies["/large-rules"] =
                "payload:\n" + (0 until 320000).joinToString("\n") { " - '+.host$it.example.test'" }
            assertTrue(fixture.bodies.getValue("/large-rules").toByteArray().size > MAX_FEED_BYTES)
            val raw =
                """
                proxies: [{name: Local fixture, type: socks5, server: example.invalid, port: 1080}]
                proxy-groups: [{name: Main, type: select, proxies: [Local fixture, DIRECT]}]
                rule-providers:
                  third: {type: http, url: '${fixture.address("/large-rules")}', behavior: domain}
                rules: ['RULE-SET,third,REJECT', 'MATCH,DIRECT']
            """
                    .trimIndent()
            val app = compose.activity.application as PingClashApplication
            val profile = app.repository.importWithProviders("Large source fixture", raw)
            try {
                assertEquals(listOf("RULE-SET,third,REJECT", "MATCH,DIRECT"), profile.rules)
                val reload =
                    ProfileRepository(app).data.value.profiles.single { it.id == profile.id }
                assertEquals(profile.definition, reload.definition)
                val cached = profile.remote!!.bodies.getValue("rule:third")
                assertTrue(cached.toByteArray().size < MAX_FEED_BYTES)
                assertTrue(ProviderBodyCache.decodedSize(cached) > MAX_FEED_BYTES)
                val imported = JSONObject(Mobile.importProfile(reload.definition))
                assertEquals("passed", imported.getString("state"))
                assertEquals(0, imported.optInt("rulesOmitted"))
                val options =
                    JSONObject()
                        .put("mode", "rule")
                        .put("rules", imported.getJSONArray("rules"))
                        .put("definition", imported.getString("definition"))
                assertEquals(
                    "passed",
                    JSONObject(Mobile.validateRuntimeOptions(options.toString())).getString("state"),
                )
                listOf(
                        "proxies: []" to "provider_rule_document",
                        "payload: [123]" to "provider_rule_syntax",
                        "<!doctype html><html>CANARY-SUB-TOKEN-1234</html>" to "provider_html",
                    )
                    .forEach { (body, code) ->
                        fixture.bodies["/large-rules"] = body
                        val failure =
                            runCatching {
                                    RemoteProfiles.load(raw, DEFAULT_SUBSCRIPTION_UA, force = true)
                                }
                                .exceptionOrNull() as ProviderFailure
                        assertEquals(code, failure.code)
                        assertEquals(1, failure.index)
                        assertFalse(failure.publicMessage().contains("CANARY-"))
                        assertEquals("1:rule:$code", providerErrorRecord("1:rule:$code"))
                    }
                val preserved = app.repository.data.value.profiles.single { it.id == profile.id }
                assertEquals(profile.definition, preserved.definition)
                fixture.bodies["/large-rules"] = "payload: ['+.example.test']"
                val declared = raw.replace("behavior: domain}", "behavior: domain, size-limit: 8}")
                val limited =
                    runCatching {
                            RemoteProfiles.load(declared, DEFAULT_SUBSCRIPTION_UA, force = true)
                        }
                        .exceptionOrNull() as ProviderFailure
                assertEquals("provider_declared_size_limit", limited.code)
                assertFalse(limited.publicMessage().contains("16 MiB"))
            } finally {
                app.repository.delete(profile.id)
            }
        }
    }

    @Test
    fun classicalNativeRulesImportWithSafeWarningsForSkippedEntries() {
        SubscriptionFixture().use { fixture ->
            fixture.bodies["/classical"] =
                """
                payload:
                  - 'PROCESS-NAME,dev.pingclash.app'
                  - 'SRC-IP-CIDR,172.29.0.0/16'
                  - 'SRC-PORT,54000'
                  - 'IP-SUFFIX,0.0.0.1/8'
                  - 'IN-TYPE,TUN'
                  - 'UID,10000-20000'
                  - 'MATCH,REJECT'
                  - 'CANARY-TYPE-1234,CANARY-PASSWORD-1234'
                  - 'DSCP,8'
                """
                    .trimIndent()
            val raw =
                """
                proxies: [{name: Local fixture, type: socks5, server: example.invalid, port: 1080}]
                rule-providers:
                  classical: {type: http, url: '${fixture.address("/classical")}', behavior: classical}
                rules: ['RULE-SET,classical,DIRECT', 'MATCH,PROXY']
            """
                    .trimIndent()
            val app = compose.activity.application as PingClashApplication
            val p = app.repository.importWithProviders("Classical compatibility", raw)
            try {
                assertEquals(2, p.providerRulesSkipped)
                assertEquals(
                    listOf("MATCH", "UNRECOGNIZED"),
                    p.providerRuleWarnings.map { it.type },
                )
                assertEquals(7, p.providerRuleWarnings.first().entry)
                val reread = ProfileRepository(app).data.value.profiles.single { it.id == p.id }
                assertEquals(p.providerRuleWarnings, reread.providerRuleWarnings)
                val checked = JSONObject(Mobile.importProfile(reread.definition))
                assertEquals("passed", checked.getString("state"))
                assertEquals(2, checked.getInt("providerRulesSkipped"))
                val options =
                    JSONObject()
                        .put("mode", "rule")
                        .put("rules", checked.getJSONArray("rules"))
                        .put("definition", checked.getString("definition"))
                assertEquals(
                    "passed",
                    JSONObject(Mobile.validateRuntimeOptions(options.toString())).getString("state"),
                )
                app.repository.activate(p.id)
                compose.waitUntil(10000) {
                    compose
                        .onAllNodesWithTag("compatibility-warning")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("compatibility-warning").performClick()
                val detail = providerRuleWarningText(reread)
                assertFalse(detail.contains("CANARY-"))
                compose.onNodeWithText(detail).assertIsDisplayed()
                val notice =
                    IssueNotice(
                        "synthetic",
                        tr(R.string.compatibility_warning),
                        detail,
                        IssueStage.PROVIDER,
                        IssueSeverity.WARNING,
                        "compatibility-warning",
                    )
                assertEquals(tr(R.string.issue_advice_compatibility), issueAdvice(notice))
                compose.onNodeWithText(issueAdvice(notice)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(tr(R.string.close)).performClick()
                val poisoned =
                    org.json
                        .JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "CANARY-SUB-TOKEN-1234")
                                .put("reason", "CANARY-PASSWORD-1234")
                                .put("count", 1)
                                .put("entry", 1)
                                .put("source", 1)
                        )
                val sanitized =
                    reread.copy(providerRuleWarnings = readProviderRuleWarnings(poisoned))
                assertFalse(providerRuleWarningText(sanitized).contains("CANARY-"))
            } finally {
                app.repository.delete(p.id)
            }
        }
    }

    @Test
    fun sevenAsnEntriesImportAndReloadWithoutSkippedRuleWarnings() {
        SubscriptionFixture().use { fixture ->
            val sources =
                listOf(
                    "payload: ['IP-ASN,13335']",
                    "payload: ['IP-ASN,15169']",
                    "payload: ['IP-ASN,37963', 'IP-ASN,45102', 'IP-ASN,16509', 'IP-ASN,8075', 'IP-ASN,32934']",
                )
            sources.forEachIndexed { index, body -> fixture.bodies["/asn-$index"] = body }
            val raw =
                """
                proxies: [{name: Local fixture, type: socks5, server: example.invalid, port: 1080}]
                rule-providers:
                  one: {type: http, url: '${fixture.address("/asn-0")}', behavior: classical}
                  two: {type: http, url: '${fixture.address("/asn-1")}', behavior: classical}
                  three: {type: http, url: '${fixture.address("/asn-2")}', behavior: classical}
                rules: ['RULE-SET,one,REJECT', 'RULE-SET,two,DIRECT', 'RULE-SET,three,PROXY', 'MATCH,PROXY']
            """
                    .trimIndent()
            val app = compose.activity.application as PingClashApplication
            assertTrue(RuleData.install(app))
            val p = app.repository.importWithProviders("ASN compatibility", raw)
            try {
                assertEquals(0, p.providerRulesSkipped)
                assertTrue(p.providerRuleWarnings.isEmpty())
                assertEquals("", providerRuleWarningText(p))
                val reread = ProfileRepository(app).data.value.profiles.single { it.id == p.id }
                assertEquals(p.rules, reread.rules)
                assertEquals(0, reread.providerRulesSkipped)
                val options =
                    JSONObject()
                        .put("mode", "rule")
                        .put("rules", org.json.JSONArray(reread.rules))
                        .put("definition", reread.definition)
                assertEquals(
                    "passed",
                    JSONObject(Mobile.validateRuntimeOptions(options.toString())).getString("state"),
                )
                val reparsed = app.repository.importProfile(p.name, p.definition, replaceId = p.id)
                assertEquals(p.nodes.map { it.id }, reparsed.nodes.map { it.id })
                assertEquals(0, reparsed.providerRulesSkipped)
                assertTrue(reparsed.providerRuleWarnings.isEmpty())
            } finally {
                app.repository.delete(p.id)
            }
        }
    }

    @Test
    fun binaryMrsSourcesPreserveBytesEncryptedReloadAndLastGoodConfiguration() {
        SubscriptionFixture().use { fixture ->
            val assets =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
            val domain = assets.open("provider-domain-fixture.mrs").use { it.readBytes() }
            val ipcidr = assets.open("provider-ipcidr-fixture.mrs").use { it.readBytes() }
            fixture.binaryBodies["/domains"] = domain
            fixture.binaryBodies["/networks"] = ipcidr
            val raw =
                """
                proxies: [{name: Local fixture, type: socks5, server: example.invalid, port: 1080}]
                proxy-groups: [{name: Main, type: select, proxies: [Local fixture, DIRECT]}]
                rule-providers:
                  domains:
                    type: http
                    url: '${fixture.address("/domains")}'
                    behavior: domain
                    format: mrs
                    path-in-bundle: geo/CANARY-PATH-1234.mrs
                  networks:
                    type: http
                    url: '${fixture.address("/networks")}'
                    behavior: ipcidr
                    format: mrs
                rules: ['AND,((NETWORK,tcp),(RULE-SET,domains)),Main', 'RULE-SET,networks,REJECT,src', 'MATCH,DIRECT']
            """
                    .trimIndent()
            val app = compose.activity.application as PingClashApplication
            val p = app.repository.importWithProviders("Binary MRS compatibility", raw)
            try {
                assertEquals(0, p.providerRulesSkipped)
                assertEquals(0, p.unsupported)
                assertEquals(
                    domain.size,
                    ProviderBodyCache.decodedSize(p.remote!!.bodies.getValue("rule:domains")),
                )
                assertEquals(
                    ipcidr.size,
                    ProviderBodyCache.decodedSize(p.remote!!.bodies.getValue("rule:networks")),
                )
                val restored = ProfileRepository(app).data.value.profiles.single { it.id == p.id }
                assertEquals(p.remote!!.bodies, restored.remote!!.bodies)
                assertEquals(listOf("Local fixture", "DIRECT"), restored.groups.single().proxies)
                val options =
                    JSONObject()
                        .put("mode", "rule")
                        .put("rules", org.json.JSONArray(restored.rules))
                        .put("definition", restored.definition)
                assertEquals(
                    "passed",
                    JSONObject(Mobile.validateRuntimeOptions(options.toString())).getString("state"),
                )
                val limited =
                    raw.replace("    path-in-bundle:", "    size-limit: 8\n    path-in-bundle:")
                assertFalse(limited == raw)
                val sizeFailure =
                    runCatching {
                            RemoteProfiles.load(limited, DEFAULT_SUBSCRIPTION_UA, force = true)
                        }
                        .exceptionOrNull() as ProviderFailure
                assertEquals("provider_declared_size_limit", sizeFailure.code)
                fixture.binaryBodies["/domains"] = "CANARY-SUB-TOKEN-1234".toByteArray()
                val failure =
                    runCatching { app.repository.refreshProviders(restored, true) }
                        .exceptionOrNull() as ProviderFailure
                assertEquals("provider_rule_document", failure.code)
                assertFalse(failure.publicMessage().contains("CANARY-"))
                assertEquals(
                    p.definition,
                    app.repository.data.value.profiles.single { it.id == p.id }.definition,
                )
            } finally {
                app.repository.delete(p.id)
            }
        }
    }
}
