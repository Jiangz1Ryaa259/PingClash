// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionNamingTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val model
        get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test
    fun headerNamesAreBoundedSanitizedAndNeverGuessedFromTokenUrls() {
        val title = "港湾订阅"
        val encoded = "base64:" + Base64.getEncoder().encodeToString(title.toByteArray())
        assertEquals(
            title,
            SubscriptionNames.fromHeaders(encoded, "attachment; filename=other.yaml"),
        )
        assertEquals(
            "测试+线路",
            SubscriptionNames.fromHeaders(
                null,
                "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95+%E7%BA%BF%E8%B7%AF.yaml",
            ),
        )
        assertEquals(
            "fixture",
            SubscriptionNames.fromHeaders("base64:!bad", "attachment; filename=fixture.yaml"),
        )
        assertEquals("AB", SubscriptionNames.fromHeaders("A\n\u202eB", null))
        assertEquals("safe", SubscriptionNames.file("../../safe.json"))
        assertEquals("Explicit", SubscriptionNames.choose("Explicit", title, "Fallback"))
        assertNull(SubscriptionNames.fromHeaders("x".repeat(4097), null))
        val emoji = SubscriptionNames.clean("🌊".repeat(80))!!
        assertTrue(emoji.length <= 100)
        assertFalse(emoji.last().isHighSurrogate())
        val source = "https://example.invalid/private/CANARY-SUB-TOKEN-1234?token=secret"
        assertEquals("", subscriptionInput(source)!!.suggestedName)
        assertFalse(subscriptionInput(source).toString().contains("CANARY"))
        val link =
            "clash://install-config?url=" +
                URLEncoder.encode(source, "UTF-8") +
                "&name=" +
                URLEncoder.encode(title, "UTF-8")
        assertEquals(title, subscriptionInput(link)!!.suggestedName)
        assertEquals(source, subscriptionInput(link)!!.source)
        assertNull(subscriptionInput("clash://install-config?url=http%3A%2F%2Fexample.invalid"))
    }

    @Test
    fun confirmedDownloadUsesHeaderNameAndExplicitNamesWinAndRefreshKeepsName() {
        val before = app.repository.data.value.profiles.map { it.id }.toSet()
        SubscriptionFixture().use { fixture ->
            try {
                fixture.profileTitleHeader =
                    "base64:" + Base64.getEncoder().encodeToString("自动标题".toByteArray())
                model.subscribe("", fixture.source)
                compose.waitUntil(10000) {
                    !model.ui.value.busy && app.repository.data.value.profiles.size > before.size
                }
                assertNull(model.ui.value.importIssue)
                val first = app.repository.data.value.profiles.last()
                assertEquals("自动标题", first.name)
                fixture.profileTitleHeader = null
                fixture.dispositionHeader = "attachment; filename*=UTF-8''Imported%20Profile.yaml"
                model.subscribe("", fixture.source)
                compose.waitUntil(10000) {
                    !model.ui.value.busy &&
                        app.repository.data.value.profiles.size == before.size + 2
                }
                assertEquals("Imported Profile", app.repository.data.value.profiles.last().name)
                model.subscribe("My choice", fixture.source)
                compose.waitUntil(10000) {
                    !model.ui.value.busy &&
                        app.repository.data.value.profiles.size == before.size + 3
                }
                assertEquals("My choice", app.repository.data.value.profiles.last().name)
                fixture.profileTitleHeader = "Changed server title"
                model.refresh(first)
                compose.waitUntil(10000) { !model.ui.value.busy }
                assertEquals(
                    "自动标题",
                    app.repository.data.value.profiles.single { it.id == first.id }.name,
                )
                val redacted = SubscriptionDownloader.fetchResponse(fixture.source).toString()
                assertFalse(redacted.contains("fixture-password"))
            } finally {
                app.repository.data.value.profiles
                    .filter { it.id !in before }
                    .forEach { app.repository.delete(it.id) }
            }
        }
    }

    @Test
    fun fileImportLivesInsideAddSheetAndOpensSystemPicker() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithText(tr(R.string.import_file)).assertDoesNotExist()
        compose.onNodeWithTag("profile-add").performClick()
        compose.onNodeWithTag("import-method-url").performClick()
        compose.onNodeWithTag("import-file").assertIsDisplayed().performClick()
        val picker = device.wait(Until.findObject(By.pkg("com.android.documentsui")), 5000)
        assertNotNull("System file picker missing", picker)
        device.pressBack()
        device.waitForIdle()
        // Close the import Activity too, before ActivityScenario tears down its owner.
        device.pressBack()
        compose.waitUntil(5000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithTag("nav-2").assertIsDisplayed()
    }
}
