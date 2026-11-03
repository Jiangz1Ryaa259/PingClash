// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Boundary coverage for the provider import bridge.

package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderImportRegressionTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun inlineProviders() =
        JSONObject().apply {
            repeat(17) { index ->
                put(
                    "set$index",
                    JSONObject()
                        .put("type", "inline")
                        .put("behavior", "domain")
                        .put("payload", JSONArray().put("+.safe$index.test")),
                )
            }
        }

    @Test
    fun downloadedSubscriptionRetainsMoreThanSixteenProvidersAndRulesEnvelope() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        SubscriptionFixture().use { fixture ->
            val providers = inlineProviders()
            providers.put(
                "remote",
                JSONObject()
                    .put("type", "http")
                    .put("behavior", "domain")
                    .put("url", fixture.address("/rules")),
            )
            fixture.bodies["/rules"] = "rules: ['+.remote.test', 'invalid..domain']"
            fixture.bodies["/feed"] =
                JSONObject()
                    .put(
                        "proxies",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", "Local")
                                    .put("type", "socks5")
                                    .put("server", "example.invalid")
                                    .put("port", 443)
                            ),
                    )
                    .put("rule-providers", providers)
                    .put("proxy-providers", JSONObject.NULL)
                    .put(
                        "rules",
                        JSONArray(
                            listOf("RULE-SET,set16,Local", "RULE-SET,remote,Local", "MATCH,REJECT")
                        ),
                    )
                    .toString()
            model.subscribe("Provider regression", fixture.address("/feed"))
            compose.waitUntil(15_000) { !model.ui.value.busy }
            assertEquals(null, model.ui.value.importIssue)
            val id = model.ui.value.importedProfileId
            assertNotNull(id)
            try {
                val profile = app.repository.data.value.profiles.single { it.id == id }
                assertEquals(1, profile.nodes.size)
                assertEquals(3, profile.rules.size)
                assertEquals(1, profile.providerRulesSkipped)
                assertEquals(1, profile.providerRuleWarnings.single().count)
                val restored = ProfileRepository(app).data.value.profiles.single { it.id == id }
                assertEquals(profile.definition, restored.definition)
            } finally {
                id?.let(app.repository::delete)
            }
        }
    }

    @Test
    fun downloadedYamlRetainsInheritedDnsAndAcceptsEmptyUse() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        SubscriptionFixture().use { fixture ->
            fixture.bodies["/feed"] =
                """
                defaults: &defaults
                  dns: {enable: true, nameserver: [1.1.1.1]}
                <<: *defaults
                proxies: [{name: Local, type: socks5, server: example.invalid, port: 443}]
                rule-providers: {source: {type: inline, behavior: domain, payload: ['+.example.test']}}
                proxy-groups: [{name: Main, type: select, use: null, proxies: [Local]}]
                rules: ['RULE-SET,source,Main', 'MATCH,REJECT']
                """
                    .trimIndent()
            model.subscribe("Inherited provider regression", fixture.address("/feed"))
            compose.waitUntil(15_000) { !model.ui.value.busy }
            assertEquals(null, model.ui.value.importIssue)
            val id = model.ui.value.importedProfileId
            assertNotNull(id)
            try {
                val profile = app.repository.data.value.profiles.single { it.id == id }
                assertEquals(2, profile.rules.size)
                val dns = JSONObject(profile.definition).getJSONObject("dns")
                assertEquals("1.1.1.1", dns.getJSONArray("nameserver").getString(0))
                assertEquals(0, profile.groupsUnsupported)
            } finally {
                id?.let(app.repository::delete)
            }
        }
    }

    @Test
    fun yamlRuleListsServedAsTxtKeepEveryEntry() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        SubscriptionFixture().use { fixture ->
            // Loyalsoldier-style lists: YAML payload documents published as ".txt".
            fixture.bodies["/direct.txt"] =
                "payload:\n" + (0 until 500).joinToString("\n") { "  - '+.d$it.example.test'" }
            fixture.bodies["/cncidr.txt"] = "payload:\n  - '1.0.1.0/24'\n  - '1.0.2.0/23'\n"
            // A plain list without a declared format stays one entry per line.
            fixture.bodies["/plain.list"] =
                "DOMAIN-SUFFIX,plain.example.test\nIP-CIDR,10.0.0.0/8,no-resolve\n"
            fixture.bodies["/feed"] =
                """
                proxies: [{name: Local, type: socks5, server: example.invalid, port: 443}]
                rule-providers:
                  direct: {type: http, behavior: domain, url: '${fixture.address("/direct.txt")}', path: ./ruleset/direct.yaml, interval: 86400}
                  cncidr: {type: http, behavior: ipcidr, url: '${fixture.address("/cncidr.txt")}', path: ./ruleset/cncidr.yaml, interval: 86400}
                  plain: {type: http, behavior: classical, url: '${fixture.address("/plain.list")}', interval: 86400}
                rules: ['RULE-SET,direct,Local', 'RULE-SET,cncidr,Local', 'RULE-SET,plain,Local', 'MATCH,DIRECT']
                """
                    .trimIndent()
            model.subscribe("Rule list formats", fixture.address("/feed"))
            compose.waitUntil(30_000) { !model.ui.value.busy }
            assertEquals(null, model.ui.value.importIssue)
            val id = model.ui.value.importedProfileId
            assertNotNull(id)
            try {
                val profile = app.repository.data.value.profiles.single { it.id == id }
                assertEquals(0, profile.providerRulesSkipped)
                assertEquals(emptyList<Any>(), profile.providerRuleWarnings)
            } finally {
                id?.let(app.repository::delete)
            }
        }
    }

    @Test
    fun rootFailureHasNoFakeProviderOrdinalOrPrivateText() {
        val message = ProviderFailure(0, "", "provider_config_limit").publicMessage()
        assertFalse(message.contains("提供源 0"))
        assertFalse(message.contains("()") || message.contains("（）"))
        assertEquals(
            tr(
                R.string.provider_configuration_failure,
                downloadFailureText("provider_config_limit"),
            ),
            message,
        )
        assertFalse(
            ProviderFailure(0, "CANARY-KIND", "CANARY-TOKEN").publicMessage().contains("CANARY-")
        )
        val warning =
            JSONObject()
                .put("source", 128)
                .put("entry", 1)
                .put("count", 1)
                .put("type", "DOMAIN")
                .put("reason", "rule_invalid")
        assertEquals(128, readProviderRuleWarnings(JSONArray().put(warning)).single().source)
        listOf(
                "provider_document_syntax",
                "provider_document_limit",
                "provider_dns_document",
                "provider_group_keys",
                "provider_group_filter",
                "provider_group_use",
                "provider_group_reference",
                "provider_rule_reference",
                "provider_rule_entry",
                "provider_bridge_invalid",
                "provider_internal",
            )
            .forEach { code ->
                assertEquals(code, safeProviderCode(code))
                assertFalse(downloadFailureText(code) == downloadFailureText("provider_network"))
            }
    }
}
