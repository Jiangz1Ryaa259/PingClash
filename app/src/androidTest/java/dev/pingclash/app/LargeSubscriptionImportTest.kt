// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Sizes of a typical large airport subscription.

package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Airport subscriptions ship dozens of regional and service groups, thousands of nodes and rule
 * lists in the tens of thousands. They import whole, survive storage, and run in rule mode.
 */
@RunWith(AndroidJUnit4::class)
class LargeSubscriptionImportTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun largeSubscription(nodes: Int, groups: Int, rules: Int) = buildString {
        appendLine("proxies:")
        repeat(nodes) {
            appendLine("  - {name: n$it, type: socks5, server: example.invalid, port: 443}")
        }
        appendLine("proxy-groups:")
        repeat(groups) {
            val kind = if (it % 3 == 1) "url-test" else "select"
            appendLine(
                "  - {name: g$it, type: $kind, proxies: [n${it % nodes}, n${(it + 1) % nodes}]," +
                    " url: 'https://www.gstatic.com/generate_204', interval: 300}"
            )
        }
        appendLine("rules:")
        repeat(rules) { appendLine("  - DOMAIN-SUFFIX,d$it.example,g${it % 20}") }
        appendLine("  - MATCH,g0")
    }

    @Test
    fun largeSubscriptionKeepsEveryGroupNodeAndRuleAndRunsInRuleMode() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        SubscriptionFixture().use { fixture ->
            fixture.bodies["/feed"] = largeSubscription(nodes = 1200, groups = 90, rules = 6000)
            model.subscribe("Large airport", fixture.address("/feed"))
            compose.waitUntil(60_000) { !model.ui.value.busy }
            assertEquals(null, model.ui.value.importIssue)
            val id = model.ui.value.importedProfileId
            assertNotNull(id)
            try {
                val profile = app.repository.data.value.profiles.single { it.id == id }
                assertEquals(1200, profile.nodes.size)
                assertEquals(90, profile.groups.size)
                assertEquals(0, profile.groupsUnsupported)
                assertEquals(90, profile.displayGroups().size)
                assertEquals(6001, profile.rules.size)
                assertEquals(0, profile.rulesOmitted)
                // Read back from encrypted storage within the same bounds.
                val restored = ProfileRepository(app).data.value.profiles.single { it.id == id }
                assertEquals(1200, restored.nodes.size)
                assertEquals(90, restored.groups.size)
                assertEquals(6001, restored.rules.size)
                // The core accepts the whole rule table in rule mode.
                app.repository.activate(profile.id)
                val data = app.repository.data.value
                VpnConfigurationFailure.runtimeOptions(
                    data.copy(client = data.client.copy(mode = "rule", useProviderRules = true))
                )
            } finally {
                id?.let(app.repository::delete)
            }
        }
    }
}
