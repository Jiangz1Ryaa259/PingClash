// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsWriteTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun anEmptySelectedAppsListSavesAndOnlyValidPackageNamesAreAccepted() {
        // Choosing "selected apps only" before picking apps is a normal state to store;
        // connecting in it is refused separately (apps_required).
        validateClientSettings(ClientSettings(appMode = "include", packages = emptyList()))
        assertTrue(validPackageName("com.example.app"))
        assertTrue(validPackageName("org.example_2.App"))
        for (bad in
            listOf("nodot", "com.example app", "1com.example", "dev.pingclash.app", "com..x")) {
            assertFalse(bad, validPackageName(bad))
        }
        assertTrue(
            runCatching {
                    validateClientSettings(
                        ClientSettings(appMode = "exclude", packages = listOf("nodot"))
                    )
                }
                .isFailure
        )
    }

    @Test
    fun rapidSettingsChangesAreQueuedNotRejectedAndNeverSetBusy() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val before = repo.data.value
        try {
            val start = before.client.copy(appMode = "exclude", packages = emptyList())
            repo.clientSettings(start, before.dnsAddress, before.probeUrl)
            val first = start.copy(packages = listOf("com.example.first"))
            val second = first.copy(packages = listOf("com.example.first", "com.example.second"))
            // Two taps in quick succession, the second before the first write finishes.
            compose.runOnUiThread {
                model.patchSettings(start, first)
                model.patchSettings(first, second)
            }
            assertFalse("a settings write must not lock the page", model.ui.value.busy)
            compose.waitUntil(10000) { model.settingsPending.value == 0 }
            assertEquals(
                listOf("com.example.first", "com.example.second"),
                repo.data.value.client.packages,
            )
        } finally {
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        }
    }
}
