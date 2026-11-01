// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber", "LongMethod") // Bounded synthetic integration fixtures.

package dev.pingclash.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportRecoveryTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.After
    fun closeImporter() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor =
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            listOf(
                    androidx.test.runner.lifecycle.Stage.RESUMED,
                    androidx.test.runner.lifecycle.Stage.STARTED,
                    androidx.test.runner.lifecycle.Stage.PAUSED,
                    androidx.test.runner.lifecycle.Stage.STOPPED,
                )
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<ProfileImportActivity>()
                .distinct()
                .forEach { it.finish() }
        }
    }

    private fun open(method: String) {
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("profile-add").performClick()
        ImportMethod.entries.forEach {
            compose.onNodeWithTag("import-method-${it.name.lowercase()}").assertHasClickAction()
        }
        compose.onNodeWithTag("import-method-$method").performClick()
        compose.onNodeWithTag("import-page").assertExists()
    }

    @Test
    fun blankTapDuringDownloadKeepsPageInteractiveAndBackCancelsBeforeCommit() {
        val app = compose.activity.application as PingClashApplication
        val before = app.repository.data.value.profiles.map { it.id }
        SubscriptionFixture().use { fixture ->
            val gate = CountDownLatch(1)
            fixture.responseGates["/slow"] = gate
            open("url")
            compose.onNodeWithTag("subscription-content").performTextInput(fixture.address("/slow"))
            hideFixtureKeyboard()
            compose.onNodeWithText(tr(R.string.ui_next)).performScrollTo().performClick()
            compose
                .onNodeWithText(tr(R.string.download_and_import))
                .performScrollTo()
                .performClick()
            compose.waitUntil(5000) { fixture.requests.get() > 0 }
            compose.onNodeWithTag("import-page").performTouchInput {
                click(center + Offset(0f, 80f))
            }
            compose
                .onNodeWithTag("import-back")
                .performScrollTo()
                .assertHasClickAction()
                .performClick()
            // finish() resumes the parent Activity asynchronously; wait for its
            // actual semantics root instead of tapping into the transition gap.
            compose.waitUntil(5000) {
                runCatching {
                        compose.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty()
                    }
                    .getOrDefault(false)
            }
            compose.onNodeWithTag("nav-0").performClick()
            compose.waitUntil(5000) {
                runCatching {
                        compose.onNodeWithTag("page-title-home").assertIsDisplayed()
                        true
                    }
                    .getOrDefault(false)
            }
            gate.countDown()
            assertTrue(fixture.responsesWritten.await(5, TimeUnit.SECONDS))
            // An idle main-thread barrier after the IO response, not a hidden sheet timeout.
            compose.waitForIdle()
            Thread.sleep(500)
            assertEquals(before, app.repository.data.value.profiles.map { it.id })
            compose.onNodeWithTag("nav-2").performClick()
            compose.onNodeWithTag("profile-add").performClick()
            compose.onNodeWithTag("import-method-text").performClick()
            compose.onNodeWithTag("subscription-content").assertIsEnabled()
            compose.onNodeWithTag("import-back").performClick()
        }
    }

    @Test
    fun explicitClipboardOffersReviewWithoutFetchingOrAutoActivating() {
        val app = compose.activity.application as PingClashApplication
        val before = app.repository.data.value.selectedId
        SubscriptionFixture().use { fixture ->
            compose.runOnUiThread {
                app.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("fixture", fixture.source))
            }
            try {
                open("clipboard")
                compose.onNodeWithTag("subscription-content").assert(hasText(fixture.source))
                val instrumentation =
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                instrumentation.runOnMainSync {
                    val activity =
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<ProfileImportActivity>()
                            .single()
                    activity.recreate()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("subscription-content").assert(hasText(fixture.source))
                assertEquals(0, fixture.requests.get())
                assertEquals(before, app.repository.data.value.selectedId)
                compose.onNodeWithTag("import-back").performClick()
            } finally {
                compose.runOnUiThread {
                    app.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                }
            }
        }
    }

    @Test
    fun localQrDecodingAcceptsImageButDoesNotFetchDecodedUrl() {
        val url = "https://example.invalid/subscribe?token=CANARY-QR-NOT-REAL"
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val pixels =
            IntArray(512 * 512) { index ->
                if (matrix[index % 512, index / 512]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        try {
            assertEquals(url, QrImport.decode(bitmap))
            val draft = ImportDraft.detect(requireNotNull(QrImport.decode(bitmap)))
            assertTrue(draft.subscription)
            assertEquals("ImportDraft(redacted)", draft.toString())
            assertFalse(
                ImportDraft.detect("anytls://fixture@example.invalid:443#test").subscription
            )
            assertFalse(ImportDraft.detect("http://example.invalid/feed").subscription)
        } finally {
            bitmap.recycle()
        }
        val blank = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            assertNull(QrImport.decode(blank))
        } finally {
            blank.recycle()
        }
    }
}
