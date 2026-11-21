// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A node typed in by hand imports through the core for every protocol it offers. */
@RunWith(AndroidJUnit4::class)
class NodeEntryTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = mainThreadComposeRule<MainActivity>()

    private val app
        get() = compose.activity.application as PingClashApplication

    private fun filled(protocol: NodeProtocol) =
        NodeEntryState().apply {
            this.protocol = protocol
            server = "192.0.2.10"
            port = "8443"
            uuid = "3f1c7a52-2b8e-4d0a-9c61-7e2f5b9d4a11"
            password = "synthetic-node-password"
            sni = "example.invalid"
            if (protocol == NodeProtocol.HYSTERIA2) {
                obfuscated = true
                obfsPassword = "synthetic-obfs"
            }
        }

    @Test
    fun everyProtocolImportsAsAProfileWithThatNode() {
        NodeProtocol.entries.forEach { protocol ->
            val node = filled(protocol)
            assertNull(protocol.name, node.problem())
            val profile = app.repository.importProfile(node.displayName(), node.definition())
            try {
                val only = profile.nodes.single()
                assertEquals(protocol.name, protocol.type, only.type)
                assertEquals("${protocol.label} 192.0.2.10", only.name)
            } finally {
                app.repository.delete(profile.id)
            }
        }
    }

    @Test
    fun missingFieldsAreNamedBeforeAnythingIsSaved() {
        val node = NodeEntryState()
        assertEquals(tr(R.string.node_address_required), node.problem())
        node.server = "example.invalid"
        node.port = "70000"
        assertEquals(tr(R.string.enter_a_valid_port), node.problem())
        node.port = "443"
        assertEquals(tr(R.string.node_secret_required), node.problem())
        node.uuid = "3f1c7a52-2b8e-4d0a-9c61-7e2f5b9d4a11"
        assertNull(node.problem())
        node.protocol = NodeProtocol.SHADOWSOCKS
        assertNotNull("Shadowsocks needs its password", node.problem())
        // The password never shows in the state's text, as for subscription drafts.
        node.password = "synthetic-secret"
        assertTrue("synthetic-secret" !in node.toString())
    }

    @Test
    fun bothManualEntriesOpenFromTheSheet() {
        listOf("node" to "node-entry", "subscription" to "subscription-ua-reset").forEach {
            (method, tag) ->
            compose.onNodeWithTag("nav-2").performClick()
            compose.onNodeWithTag("profile-add").performClick()
            compose.chooseImport(method)
            compose.waitUntil(10_000) {
                compose
                    .onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                Stage.entries
                    .flatMap {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                    }
                    .filterIsInstance<ProfileImportActivity>()
                    .forEach { it.finish() }
            }
            compose.waitForIdle()
        }
    }
}
