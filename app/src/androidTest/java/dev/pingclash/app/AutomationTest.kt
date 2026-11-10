// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun automationSettingsRoundTripAndBadValuesFallBackOrAreRefused() {
        val value =
            ClientSettings(
                startOnBoot = true,
                subscriptionHours = 6,
                wifiAuto = true,
                trustedWifi = listOf("Home", "家里的网络"),
            )
        assertEquals(value, ClientSettings.read(value.json()))
        // Old settings without these keys keep today's behaviour: daily updates, nothing else.
        val old = ClientSettings.read(JSONObject().put("mode", "rule"))
        assertFalse(old.startOnBoot || old.wifiAuto)
        assertEquals(24, old.subscriptionHours)
        val odd =
            ClientSettings.read(
                value
                    .json()
                    .put("subscriptionHours", 5)
                    .put(
                        "trustedWifi",
                        org.json.JSONArray(listOf("", "a".repeat(33), "Cafe", "Cafe")),
                    )
            )
        assertEquals(24, odd.subscriptionHours)
        assertEquals(listOf("Cafe"), odd.trustedWifi)
        validateClientSettings(value)
        for (bad in
            listOf(
                value.copy(subscriptionHours = 5),
                value.copy(trustedWifi = listOf("Home", "Home")),
                value.copy(trustedWifi = (1..33).map { "net$it" }),
            )) {
            assertTrue(runCatching { validateClientSettings(bad) }.isFailure)
        }
        // These change nothing in a running tunnel, so they stay editable while connected.
        assertTrue(
            listOf("startOnBoot", "subscriptionHours", "wifiAuto", "trustedWifi").all {
                it in liveAppearanceFields
            }
        )
    }

    @Test
    fun subscriptionsUpdateAtTheChosenIntervalAndNeverWhenOff() {
        val now = 1_000_000L
        val profile =
            profile("a", "https://sub.example/x").copy(subscriptionUpdated = now - 6 * 3600)
        assertEquals(0L, subscriptionDueIn(profile, 6, now))
        assertEquals(6 * 3600L, subscriptionDueIn(profile, 12, now))
        assertEquals(Long.MAX_VALUE, subscriptionDueIn(profile, 0, now))
        // A periodic job may run a little early; an eighth of the interval still counts.
        assertEquals(
            0L,
            subscriptionDueIn(
                profile.copy(subscriptionUpdated = now - 5 * 3600 - 1800),
                6,
                now,
                early = true,
            ),
        )
        // Local profiles never download.
        assertEquals(Long.MAX_VALUE, subscriptionDueIn(profile("b", null), 6, now))
        assertEquals("每 6 小时", updateIntervalLabel(6))
        assertEquals("每天", updateIntervalLabel(24))
        assertEquals("每 3 天", updateIntervalLabel(72))
        assertEquals("关闭", updateIntervalLabel(0))
    }

    @Test
    fun aSettingsBackupLeavesOutLinksAndPasswordsUnlessTheUserIncludesThem() {
        val secret = "https://sub.example/api?token=very-secret-token"
        val data =
            AppData(
                profiles = listOf(profile("remote", secret), profile("local", null)),
                client = ClientSettings(mode = "global", startOnBoot = true),
            )
        val config = "proxies:\n  - {name: n, type: ss, password: hunter2}\n"
        val plain = SettingsBackup.export(data, false) { config }
        assertFalse(plain.contains("very-secret-token"))
        assertFalse(plain.contains("hunter2"))
        val restored = SettingsBackup.read(plain)
        assertEquals("global", restored.settings.mode)
        assertTrue(restored.settings.startOnBoot)
        assertTrue(restored.profiles.isEmpty())

        val full = SettingsBackup.read(SettingsBackup.export(data, true) { config })
        assertEquals(listOf(secret, null), full.profiles.map { it.subscription })
        assertEquals(config, full.profiles[1].configuration)
        // Never printed by accident in a log or crash report.
        assertFalse(
            full.toString().contains("token") || full.profiles.toString().contains("hunter2")
        )

        for (bad in
            listOf(
                "not json",
                "{}",
                JSONObject(plain).put("format", "other").toString(),
                JSONObject(plain).put("version", 99).toString(),
                JSONObject(plain).put("settings", JSONObject().put("mtu", 1)).toString(),
            )) {
            assertTrue(
                bad,
                runCatching { SettingsBackup.read(bad) }.exceptionOrNull() is SettingsFailure,
            )
        }
        // A plain-http link in a backup is dropped, as on import.
        val http =
            JSONObject(plain)
                .put(
                    "profiles",
                    org.json
                        .JSONArray()
                        .put(
                            JSONObject().put("name", "x").put("subscription", "http://a.example/s")
                        ),
                )
        assertTrue(SettingsBackup.read(http.toString()).profiles.isEmpty())
        val huge = ByteArray(SettingsBackup.MAX_BYTES + 1) { ' '.code.toByte() }
        assertTrue(runCatching { SettingsBackup.readFile(huge.inputStream()) }.isFailure)
    }

    @Test
    fun theTileWidgetAndReceiversAreDeclaredAndOnlyTheSystemFacingOnesAreExported() {
        val context = compose.activity
        val packages = context.packageManager
        fun service(cls: Class<*>) =
            packages.getServiceInfo(ComponentName(context, cls), PackageManager.GET_META_DATA)
        fun receiver(cls: Class<*>) =
            packages.getReceiverInfo(ComponentName(context, cls), PackageManager.GET_META_DATA)
        val tile = service(ConnectTileService::class.java)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertEquals(
            "android.permission.BIND_JOB_SERVICE",
            service(SubscriptionUpdateJob::class.java).permission,
        )
        // Other apps must not be able to switch the VPN through these.
        assertFalse(receiver(WidgetToggleReceiver::class.java).exported)
        assertFalse(receiver(NetworkChangeReceiver::class.java).exported)
        assertTrue(receiver(ConnectWidget::class.java).exported)
        // Drawing the widget and the tile state with nothing placed must be harmless.
        ConnectWidget.refresh(context)
        ConnectTileService.refresh(context)
        assertEquals("未连接", shortcutStatus(VpnUiState()))
        assertEquals("已连接", shortcutStatus(VpnUiState("established")))
    }

    @Test
    fun automationAndBackupPagesOpenFromSettingsAndSaveSwitches() {
        val repo = (compose.activity.application as PingClashApplication).repository
        val before = repo.data.value
        try {
            compose.onNodeWithTag("nav-3").performClick()
            compose.waitForIdle()
            visibleControl("settings-automation").performClick()
            compose.waitForIdle()
            val boot = compose.onNodeWithTag("switch-开机时自动连接")
            // The page slides in; wait until it has arrived.
            compose.waitUntil(5000) { runCatching { boot.assertIsDisplayed() }.isSuccess }
            boot.assertIsOff().performClick()
            compose.waitUntil(10000) { repo.data.value.client.startOnBoot }
            boot.assertIsOn()
            compose.onNodeWithTag("switch-按网络自动连接").performScrollTo().assertIsOff()
            compose.onNodeWithTag("settings-back").performClick()
            compose.waitForIdle()
            // Backup and restore now sit on the Privacy page.
            visibleControl("settings-privacy").performClick()
            compose.waitForIdle()
            val export = compose.onNodeWithTag("backup-export")
            compose.waitUntil(5000) {
                runCatching { export.performScrollTo().assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithTag("backup-restore").performScrollTo().assertIsDisplayed()
        } finally {
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        }
    }

    // ScrollTo sees the full viewport; the floating navigation bar still owns its taps.
    private fun visibleControl(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        val navTop = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot.top
        val clearance = 12 * compose.activity.resources.displayMetrics.density
        val distance = node.fetchSemanticsNode().boundsInRoot.bottom - navTop + clearance
        if (distance > 0)
            compose.onNodeWithTag("settings-page").performSemanticsAction(
                SemanticsActions.ScrollBy
            ) {
                it(0f, distance)
            }
        compose.waitForIdle()
        return node
    }

    private fun profile(id: String, source: String?) =
        LocalProfile(id, id, source, emptyList(), 0, false)
}
