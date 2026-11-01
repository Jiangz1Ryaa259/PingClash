package dev.pingclash.app

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkSecurityTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val app
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PingClashApplication

    private fun link(source: String, name: String = "Synthetic profile") =
        "clash://install-config?url=" +
            URLEncoder.encode(source, "UTF-8") +
            "&name=" +
            URLEncoder.encode(name, "UTF-8")

    private fun intent(raw: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(raw), app, SubscriptionImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun currentActivity(): SubscriptionImportActivity? {
        var result: SubscriptionImportActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SubscriptionImportActivity>()
                    .firstOrNull()
        }
        return result
    }

    private fun openLink(raw: String) {
        app.startActivity(intent(raw))
        compose.waitUntil(10000) { currentActivity() != null }
    }

    private fun closeLink() {
        val activity = currentActivity() ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        compose.waitUntil(10000) { currentActivity() == null }
    }

    @Test
    fun untrustedLinkParsingRejectsAmbiguityAndSanitizesNames() {
        val good =
            link(
                "https://example.invalid/sub?token=CANARY-SUB-TOKEN-1234",
                "A\n\u202eB\u0000" + "x".repeat(150),
            )
        val parsed = requireNotNull(SubscriptionDeepLink.parse(good))
        assertEquals("example.invalid", parsed.host)
        assertEquals(64, parsed.name.codePointCount(0, parsed.name.length))
        assertFalse(
            parsed.name.any {
                Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
            }
        )
        assertFalse(parsed.toString().contains("CANARY-"))
        // Unknown well-formed parameters are ignored and never change the target.
        assertEquals(
            parsed.source,
            requireNotNull(SubscriptionDeepLink.parse("$good&activate=1&ref=panel")).source,
        )
        for (bad in
            listOf(
                link("http://example.invalid/sub"),
                link("file:///data/private"),
                link("javascript:alert(1)"),
                link("https://user:password@example.invalid/sub"),
                link("https://example.invalid/sub#fragment"),
                "$good&url=https%3A%2F%2Fother.invalid",
                "$good&%75rl=https%3A%2F%2Fother.invalid",
                "$good&name=duplicate",
                good.replace("install-config", "other-host"),
                good.replace("install-config?", "install-config/other?"),
                "$good#hidden",
                "$good&bad=%ZZ",
                "clash://install-config?url=" + "x".repeat(17000),
            )) assertNull("Unsafe link accepted", SubscriptionDeepLink.parse(bad))
        assertNull(SubscriptionDeepLink.parse(null))
    }

    @Test
    fun externalLinkWaitsForConsentAndNeverActivatesAfterImport() {
        SubscriptionFixture().use { fixture ->
            val before = app.repository.data.value
            val url = fixture.address("/subscribe?token=CANARY-SUB-TOKEN-1234")
            openLink(link(url))
            try {
                assertNull(requireNotNull(currentActivity()).intent.data)
                compose.onNodeWithText("127.0.0.1", substring = true).assertIsDisplayed()
                compose.onAllNodes(hasText("CANARY-", substring = true)).assertCountEquals(0)
                assertEquals(0, fixture.requests.get())
                assertEquals(before, app.repository.data.value)
                val old = requireNotNull(currentActivity())
                InstrumentationRegistry.getInstrumentation().runOnMainSync { old.recreate() }
                compose.waitUntil(10000) { currentActivity()?.let { it !== old } == true }
                compose.onNodeWithTag("deep-link-confirm").assertIsDisplayed()
                assertEquals(0, fixture.requests.get())
                compose.onNodeWithTag("deep-link-confirm").performClick()
                compose.waitUntil(15000) {
                    app.repository.data.value.profiles.size == before.profiles.size + 1
                }
                compose.onNodeWithTag("deep-link-close").assertIsDisplayed()
                assertInactiveThenActivate(before)
            } finally {
                closeLink()
            }
        }
    }

    private fun assertInactiveThenActivate(before: AppData) {
        val after = app.repository.data.value
        val previousIds = before.profiles.map { it.id }.toSet()
        val imported = after.profiles.single { it.id !in previousIds }
        try {
            assertTrue(imported.activationRequired)
            assertEquals(before.activeProfile?.id, after.activeProfile?.id)
            assertEquals(before.selectedId, after.selectedId)
            assertFalse(VpnRuntime.state.value.active)
            val restored = ProfileRepository(app).data.value
            assertTrue(restored.profiles.single { it.id == imported.id }.activationRequired)
            assertEquals(before.activeProfile?.id, restored.activeProfile?.id)
            app.repository.activate(imported.id)
            assertFalse(
                app.repository.data.value.profiles
                    .single { it.id == imported.id }
                    .activationRequired
            )
            assertEquals(imported.id, app.repository.data.value.activeProfile?.id)
        } finally {
            app.repository.delete(imported.id)
            before.activeProfile?.let { app.repository.activate(it.id) }
            before.selectedId?.let { app.repository.select(it) }
        }
    }

    @Test
    fun cancelAndInvalidSchemeNeverDownload() {
        SubscriptionFixture().use { fixture ->
            openLink(link(fixture.source))
            try {
                compose.onNodeWithTag("deep-link-cancel").performClick()
                compose.waitUntil(10000) { currentActivity() == null }
            } finally {
                closeLink()
            }
            openLink(link("http://127.0.0.1/sub"))
            try {
                compose.onNodeWithTag("deep-link-close").assertIsDisplayed()
                compose.onAllNodesWithTag("deep-link-confirm").assertCountEquals(0)
            } finally {
                closeLink()
            }
            assertEquals(0, fixture.requests.get())
        }
    }

    @Test
    fun firstImportedProfileCannotBecomeFallbackUntilExplicitActivation() {
        val directory = File(app.cacheDir, "deep-link-test-" + UUID.randomUUID()).apply { mkdirs() }
        val isolated =
            object : ContextWrapper(app) {
                override fun getFilesDir() = directory
            }
        val repository = ProfileRepository(isolated)
        val text =
            "proxies: [{name: synthetic, type: anytls, server: example.invalid, " +
                "port: 443, password: CANARY-PASSWORD-1234}]\n" +
                "proxy-groups: [{name: main, type: select, proxies: [synthetic]}]"
        try {
            val first = repository.importProfile("Pending", text, requireActivation = true)
            assertNull(repository.data.value.activeProfile)
            assertNull(repository.data.value.selected)
            assertNull(ProfileRepository(isolated).data.value.activeProfile)
            repository.reparse(first.id)
            assertNull(repository.data.value.activeProfile)
            val other = repository.importProfile("Previously active", text)
            repository.delete(other.id)
            assertNull(repository.data.value.activeProfile)
            assertNull(repository.data.value.selected)
            repository.select(repository.data.value.profiles.single().nodes.single().id)
            assertEquals(first.id, repository.data.value.activeProfile?.id)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
