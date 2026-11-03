// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Synthetic archive limits and fixed protocol fixtures.

package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pingclash.mobile.Mobile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MergeAuditTest {
    @get:Rule val locale = ResourceLocaleRule()

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun newImportDuringVPNDoesNotChangeWorkingProfile() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PingClashApplication
        val repository = app.repository
        val oldVPN = VpnRuntime.state.value
        val applied = repository.data.value
        val selected = repository.data.value.selectedId
        var imported: LocalProfile? = null
        try {
            VpnRuntime.publish(VpnUiState(VpnPhase.ESTABLISHED.wire))
            imported =
                repository.importProfile(
                    "Synthetic staged import",
                    "proxies: [{name: staged, type: socks5, server: 127.0.0.1, port: 9}]",
                )
            assertTrue(imported.activationRequired)
            assertEquals(selected, repository.data.value.selectedId)
            val staged = requireNotNull(imported)
            repository.activate(staged.id)
            repository.restoreRouting(applied, repository.data.value)
            assertEquals(applied.activeProfileId, repository.data.value.activeProfileId)
            assertEquals(selected, repository.data.value.selectedId)
            assertTrue(
                repository.data.value.profiles.single { it.id == staged.id }.activationRequired
            )
        } finally {
            VpnRuntime.publish(oldVPN)
            imported?.let { repository.delete(it.id) }
        }
    }

    @Test
    fun proxySortingOnlyChangesPresentationAndKeepsUntestedRows() {
        val official = listOf("z", "a", "failed", "untested")
        val values =
            mapOf(
                "z" to ProxyDelay("passed", 200),
                "a" to ProxyDelay("passed", 100),
                "failed" to ProxyDelay("timeout"),
            )
        assertEquals(
            listOf("a", "z", "untested"),
            orderedProxyMembers(official, ProxySort.LATENCY, true, values::get),
        )
        assertEquals(listOf("z", "a", "failed", "untested"), official)
        assertEquals(
            official,
            orderedProxyMembers(official, ProxySort.SUBSCRIPTION, false, values::get),
        )
    }

    @Test
    fun authorizedFileProviderBundleResolvesWithoutFilesystemAccess() {
        val text =
            """
            proxy-providers:
              local: {type: file, path: ./providers/nodes.yaml}
            proxy-groups: [{name: Main, type: select, use: [local]}]
            rules: [MATCH,Main]
            """
                .trimIndent()
                .replace("rules: [MATCH,Main]", "rules: ['MATCH,Main']")
        val bytes =
            archive(
                "config.yaml" to text,
                "providers/nodes.yaml" to
                    "proxies: [{name: n, type: socks5, server: 127.0.0.1, port: 9}]",
            )
        val document = ProfileDocument.read(bytes.inputStream())
        val prepared = RemoteProfiles.loadDocument(document, DEFAULT_SUBSCRIPTION_UA)
        assertEquals(
            "passed",
            JSONObject(Mobile.importProfile(prepared.content)).getString("state"),
        )
        assertEquals(1, document.localBodies.size)
        assertEquals("ProfileDocument(redacted)", document.toString())
    }

    @Test
    fun archiveTraversalAmbiguousConfigsAndExpansionAreRejected() {
        val invalid =
            listOf(
                archive("config.yaml" to "{}", "../escape" to "CANARY-ESCAPE"),
                archive("config.yaml" to "{}", "config.json" to "{}"),
                archive("config.yaml" to "{}", "./config.yaml" to "{}"),
                archive("config.yaml" to "x".repeat(MAX_FEED_BYTES + 1)),
            )
        invalid.forEach { bytes ->
            assertTrue(runCatching { ProfileDocument.read(bytes.inputStream()) }.isFailure)
        }
        listOf("/absolute", "C:/private", "a\\b", "a/../b", "a//b").forEach { path ->
            assertTrue(runCatching { ProfileDocument.bundlePath(path) }.isFailure)
        }
    }

    @Test
    fun explicitExportRebuildsStandardProvidersAndPreservesDNSOrder() {
        val definition =
            JSONObject()
                .put("proxies", org.json.JSONArray())
                .put("dns", JSONObject())
                .put("pingclash-selections", JSONObject().put("Main", "CANARY-SELECTION"))
                .put(
                    "pingclash-native-dns",
                    "dns: {nameserver-policy: {z.invalid: 1.1.1.1, a.invalid: 1.0.0.1}}",
                )
                .put(
                    "pingclash-rule-data",
                    """{"set":{"behavior":"domain","entries":["+.example.invalid"]}}""",
                )
        val result = Mobile.exportConfiguration(definition.toString())
        assertFalse(result.contains("pingclash-"))
        assertFalse(result.contains("CANARY-SELECTION"))
        assertTrue(result.indexOf("z.invalid") < result.indexOf("a.invalid"))
        assertEquals(
            "inline",
            JSONObject(result)
                .getJSONObject("rule-providers")
                .getJSONObject("set")
                .getString("type"),
        )
    }

    @Test
    fun importErrorsExposeFixedCategoryWithoutUnderlyingCredentials() {
        val secret = "CANARY-SUB-TOKEN-1234"
        val generic = safeImportFailure(IllegalArgumentException("https://example.invalid/$secret"))
        val provider = safeImportFailure(ProviderFailure(3, "rule", "provider_size_limit"))
        assertFalse(generic.contains(secret))
        assertFalse(provider.contains(secret))
        assertNotEquals(generic, provider)
    }
}
