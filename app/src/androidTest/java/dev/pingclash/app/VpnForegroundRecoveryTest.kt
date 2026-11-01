// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Bounded synthetic TUN and real offline-renderer integration.

package dev.pingclash.app

import android.content.Intent
import android.net.VpnService
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnForegroundRecoveryTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun geometryMeasuredBeforeResumeIsRetainedWithoutEnablingTheRenderer() {
        compose.runOnUiThread {
            val state = LanyardThreeState()
            val token = Any()
            try {
                state.control(token, true)
                assertFalse(state.active)
                state.slot(token, 120f, 240f, 180f)
                assertTrue(state.hasControl)
                assertFalse(state.active)
                state.slot(Any(), 0f, 0f)
                assertTrue(state.hasControl)
                val pose = LanyardPose().apply { holding = true }
                state.pose(
                    token,
                    pose,
                    androidx.compose.ui.geometry.Offset.Zero,
                    androidx.compose.ui.unit.Density(1f),
                    ThreeControlText("established", "", ""),
                )
                assertTrue(state.dragging)
                state.control(token, true)
                assertTrue("Rebinding reset an active drag", state.dragging)
            } finally {
                state.dispose()
            }
        }
    }

    @Test
    fun appSwitchesAndActivityRecreationRecoverTheLiveServiceWithoutStartingAgain() =
        withVpn { app ->
            awaitBack()
            val renderer = requireNotNull(renderer())
            val original = status(renderer)
            val service = PingVpnService.instance
            val started = VpnRuntime.state.value.sessionStartedElapsed
            repeat(4) {
                device.pressHome()
                device.waitForIdle(500)
                if (it == 2) VpnRuntime.publish(VpnUiState())
                reopen(app, notification = it % 2 == 0)
                awaitBack()
                assertTrue(renderer === renderer())
                assertTrue(service === PingVpnService.instance)
                assertEquals(started, VpnRuntime.state.value.sessionStartedElapsed)
                assertEquals(
                    original.getJSONObject("pose").getInt("entries"),
                    status(renderer).getJSONObject("pose").getInt("entries"),
                )
            }
            // Reproduce an idle UI projection while the owned TUN is provably still running.
            compose.runOnUiThread {
                VpnRuntime.publish(VpnUiState())
                VpnRuntime.connect(app)
            }
            awaitBack()
            assertTrue(service === PingVpnService.instance)
            assertEquals(started, VpnRuntime.state.value.sessionStartedElapsed)
            compose.activityRule.scenario.recreate()
            awaitBack(10)
            assertTrue(service === PingVpnService.instance)
            assertEquals(started, VpnRuntime.state.value.sessionStartedElapsed)
            compose.onNodeWithTag("vpn-power").assertIsEnabled().performClick()
            compose.waitUntil(10000) {
                !VpnRuntime.state.value.active && PingVpnService.instance == null
            }
            assertFalse(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
        }

    @Test
    fun rendererProcessCrashPreservesVpnAndNativeFallbackCanDisconnect() = withVpn { app ->
        awaitBack()
        val view = requireNotNull(renderer())
        val started = VpnRuntime.state.value.sessionStartedElapsed
        // Android's documented renderer-termination test. No production crash switch exists.
        compose.runOnUiThread { view.loadUrl("chrome://crash") }
        await { renderer() == null }
        compose.waitForIdle()
        compose.onNodeWithTag("lanyard-minimal-fallback", useUnmergedTree = true).assertExists()
        assertEquals("established", VpnRuntime.state.value.phase)
        assertEquals(started, VpnRuntime.state.value.sessionStartedElapsed)
        assertTrue(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
        device.pressHome()
        reopen(app)
        compose.waitForIdle()
        assertNull(renderer())
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertNull(renderer())
        assertEquals("established", VpnRuntime.state.value.phase)
        compose.onNodeWithTag("vpn-power").assertIsEnabled().performClick()
        compose.waitUntil(10000) {
            !VpnRuntime.state.value.active && PingVpnService.instance == null
        }
        assertFalse(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
    }

    private fun withVpn(test: (PingClashApplication) -> Unit) {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        ControlledServers().use { fixture ->
            val profile =
                repo.importProfile(
                    "Foreground fixture",
                    JSONObject()
                        .put("type", "socks5")
                        .put("name", "Foreground fixture")
                        .put("server", "127.0.0.1")
                        .put("port", fixture.socks.localPort)
                        .put("username", "fixture-user")
                        .put("password", "fixture-private-password")
                        .toString(),
                )
            try {
                repo.select(profile.nodes.single().id)
                repo.clientSettings(
                    before.client.copy(
                        appMode = "all",
                        packages = emptyList(),
                        reduceEffects = false,
                        scene = "none",
                    ),
                    "127.0.0.1:${fixture.dns.localPort}",
                    before.probeUrl,
                )
                val consent = VpnService.prepare(app)
                if (consent != null) {
                    compose.runOnUiThread {
                        compose.activity.startActivityForResult(consent, 12039)
                    }
                    approveSyntheticVpn(device, compose)
                }
                VpnRuntime.connect(app)
                compose.waitUntil(20000) {
                    VpnRuntime.state.value.phase in setOf("established", "error")
                }
                assertEquals("established", VpnRuntime.state.value.phase)
                test(app)
            } finally {
                if (PingVpnService.instance != null)
                    app.startService(
                        Intent(app, PingVpnService::class.java).setAction(PingVpnService.DISCONNECT)
                    )
                compose.waitUntil(10000) {
                    PingVpnService.instance == null && !VpnRuntime.state.value.active
                }
                repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                repo.delete(profile.id)
            }
        }
    }

    private fun reopen(app: PingClashApplication, notification: Boolean = false) {
        val intent =
            if (notification) vpnActivityIntent(app)
            else
                requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        app.startActivity(intent)
        device.waitForIdle(500)
        var focused = false
        compose.waitUntil(5000) {
            compose.activityRule.scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }
        assertTrue("Original main Activity did not return to foreground", focused)
    }

    private fun awaitBack(seconds: Long = 40) =
        await(seconds) {
            val view = renderer()
            if (view == null || VpnRuntime.state.value.phase != "established") false
            else if (
                compose
                    .onNodeWithTag("vpn-power")
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
            )
                false
            else
                status(view).let {
                    it.optBoolean("ready") &&
                        it.optString("paintedPhase") == "established" &&
                        it.optJSONObject("pose")?.optString("stage") == "idle" &&
                        it.optJSONObject("pose")?.optDouble("normalZ", 1.0)?.let { z -> z < -.6 } ==
                            true
                }
        }

    private fun renderer(): WebView? {
        var result: WebView? = null
        compose.activityRule.scenario.onActivity { activity ->
            result = find(activity.window.decorView)
        }
        return result
    }

    private fun find(view: View): WebView? =
        when {
            view is WebView && view.tag == "lanyard-offline-webview" -> view
            view is ViewGroup ->
                (0 until view.childCount)
                    .asSequence()
                    .mapNotNull { find(view.getChildAt(it)) }
                    .firstOrNull()
            else -> null
        }

    private fun status(view: WebView): JSONObject {
        val latch = CountDownLatch(1)
        var value = "{}"
        compose.runOnUiThread {
            view.evaluateJavascript("JSON.stringify(window.PingClashScene?.status()||{})") {
                value = JSONTokener(it).nextValue().toString()
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return JSONObject(value)
    }

    private fun await(seconds: Long = 40, test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (test()) return
            Thread.sleep(100)
        }
        val view = renderer()
        val details = view?.let { status(it).toString() } ?: "no-renderer"
        val fallback =
            compose
                .onAllNodesWithTag("lanyard-minimal-fallback", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        captureSyntheticFixture(compose, device, "foreground-recovery-failed.png")
        device.dumpWindowHierarchy(
            java.io.File(
                compose.activity.getExternalFilesDir(null),
                "foreground-recovery-failed.xml",
            )
        )
        assertTrue(
            "Foreground recovery: phase=${VpnRuntime.state.value.phase}; fallback=$fallback; $details",
            test(),
        )
    }
}
