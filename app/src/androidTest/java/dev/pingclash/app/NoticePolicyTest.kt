// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Functional countdown and fixture bounds.

package dev.pingclash.app

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoticePolicyTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun compactReminderFuseCountsFiveSecondsWithAndWithoutDecorativeMotion() {
        for (motion in listOf(true, false)) {
            val center = IssueNoticeCenter()
            fixture(center, motion) {}
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread { center.publish(notice(IssueSeverity.REMINDER)) }
                compose.mainClock.advanceTimeBy(350)
                compose.waitForIdle()
                val card = compose.onNodeWithTag("fuse-fixture")
                card.assertIsDisplayed()
                assertCompact(card)
                val start = fraction()
                assertTrue(start > .85f)
                compose.mainClock.advanceTimeBy(2200)
                compose.waitForIdle()
                assertTrue(fraction() in 0.35f..0.60f)
                assertTrue(fraction() < start)
                compose.mainClock.advanceTimeBy(3300)
                card.assertDoesNotExist()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun warningRimStaysFullUntilInspectedAndDoesNotAutoDismiss() {
        val center = IssueNoticeCenter()
        fixture(center) {}
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { center.publish(notice(IssueSeverity.WARNING)) }
            compose.mainClock.advanceTimeBy(14000)
            compose.waitForIdle()
            compose.onNodeWithTag("fuse-fixture").assertIsDisplayed()
            assertCompact(compose.onNodeWithTag("fuse-fixture"))
            assertEquals(1f, fraction(), 0f)
            compose.onNodeWithTag("fuse-fixture").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("fuse-fixture").assertDoesNotExist()
            compose.onNodeWithTag("fuse-fixture-detail").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun successfulImportAndReparseDoNotPublishSuccessToasts() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        fixture(model.notices) {}
        compose.waitUntil(10000) { app.nativeReady }
        model.importText("Silent success fixture", validProfile)
        compose.waitUntil(10000) {
            model.ui.value.importedProfileId != null && !model.ui.value.busy
        }
        val id = requireNotNull(model.ui.value.importedProfileId)
        try {
            assertNull(model.ui.value.message)
            compose.runOnUiThread { model.importCompleted(id) }
            assertTrue(model.notices.entries.isEmpty())
            app.repository.activate(id)
            val before = app.repository.data.value.activeProfile
            assertEquals(id, before?.id)
            model.reparse(requireNotNull(before))
            compose.waitUntil(10000) { !model.ui.value.busy }
            assertNull(model.ui.value.message)
            assertTrue(model.notices.entries.isEmpty())
        } finally {
            app.repository.delete(id)
        }
    }

    @Test
    fun partialImportsStillPublishARealCompatibilityWarning() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        fixture(model.notices) {}
        compose.waitUntil(10000) { app.nativeReady }
        val raw =
            JSONObject(validProfile)
                .apply {
                    getJSONArray("proxies")
                        .put(
                            JSONObject()
                                .put("name", "invalid")
                                .put("type", "anytls")
                                .put("server", "example.invalid")
                                .put("port", 443)
                                .put("password", "fixture")
                                .put("udp", "invalid-flag")
                        )
                }
                .toString()
        val profile = app.repository.importProfile("Partial fixture", raw)
        try {
            assertEquals(1, profile.unsupported)
            compose.runOnUiThread { model.importCompleted(profile.id) }
            compose.runOnIdle {
                assertEquals(IssueSeverity.WARNING, model.notices.current()?.severity)
            }
            assertNull(model.ui.value.message)
        } finally {
            app.repository.delete(profile.id)
        }
    }

    @Test
    fun expiryClipboardDoesNotContainCredentialBearingProfileNames() {
        val center = IssueNoticeCenter()
        val name = "https://example.invalid/sub?token=CANARY-NOTICE-SECRET"
        val profile =
            profile(name).copy(quota = SubscriptionQuota(expire = Instant.now().epochSecond - 60))
        fixture(center) { SubscriptionExpiryNotice(profile, center) }
        compose.runOnIdle {
            val item = requireNotNull(center.current())
            assertTrue(copyIssueSummary(compose.activity, item))
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
            assertFalse(clip?.getItemAt(0)?.text.toString().contains("CANARY-NOTICE-SECRET"))
            assertFalse(item.detail.contains(name))
        }
    }

    @Test
    fun cachedProviderErrorsAreVisibleAndUseOnlyPublicFailureLabels() {
        val center = IssueNoticeCenter()
        val profile =
            profile("CANARY-NOTICE-SECRET")
                .copy(
                    remote =
                        RemoteProfile(
                            "{}",
                            emptyMap(),
                            emptyMap(),
                            error = "3:rule:provider_network",
                        )
                )
        fixture(center) { ProviderFetchNotice(profile, center) }
        compose.onNodeWithTag("provider-fetch-warning").assertIsDisplayed()
        compose.runOnIdle {
            val item = requireNotNull(center.current())
            assertEquals(IssueSeverity.WARNING, item.severity)
            assertEquals(IssueStage.PROVIDER, item.stage)
            assertFalse(item.detail.contains("CANARY-"))
            assertEquals(
                ProviderFailure(3, "rule", "provider_network").publicMessage(),
                item.detail,
            )
        }
    }

    @Test
    fun foregroundAndBackgroundReportsOfTheSameFailureAreNotDuplicated() {
        val center = IssueNoticeCenter()
        val item = notice(IssueSeverity.WARNING)
        center.publish(item)
        center.publish(item.copy(id = "another-path"))
        assertEquals(1, center.entries.size)
        center.publish(item.copy(id = "different-stage", stage = IssueStage.DNS))
        assertEquals(2, center.entries.size)
        assertNotNull(center.current())
    }

    private fun fraction() =
        compose.onNodeWithTag("fuse-fixture").fetchSemanticsNode().config[NoticeFuseFraction]

    private fun assertCompact(node: SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(bounds.height <= 56 * density + 1)
        assertTrue(bounds.width <= 260 * density + 1)
    }

    private fun profile(name: String) =
        LocalProfile("notice-policy", name, null, emptyList(), 0, false)

    private fun notice(severity: IssueSeverity) =
        IssueNotice(
            "fuse",
            tr(R.string.issue_reminder),
            tr(R.string.vpn_dns_invalid),
            IssueStage.OPERATION,
            severity,
            "fuse-fixture",
        )

    private fun fixture(
        center: IssueNoticeCenter,
        motion: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides motion,
                        LocalIssueNotices provides center,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            content()
                            IssueNoticeHost(center)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val validProfile =
        """
        {"proxies":[{"name":"valid","type":"anytls",
        "server":"example.invalid","port":443,"password":"fixture"}]}
        """
            .trimIndent()
}
