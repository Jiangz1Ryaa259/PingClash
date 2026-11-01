// SPDX-License-Identifier: GPL-3.0-only
// Synthetic renderer fixture: no profile, credentials or real VPN connection.
package dev.pingclash.app

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanyardThreeTest {
    private lateinit var control: LanyardThreeState
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun originalThreeSceneRendersAndNativeActionControlsBothFaces() {
        val phase = mutableStateOf("idle")
        val motion = mutableStateOf(true)
        fixture {
            CompositionLocalProvider(LocalMotionAllowed provides motion.value) {
                LanyardPowerControl(phase.value, true, "Fixture action") {
                    phase.value = if (phase.value == "idle") "connecting" else "stopping"
                }
            }
        }
        val view = renderer()
        await { status(view).optBoolean("ready") }
        val rendered = status(view)
        assertTrue(
            "Native frame delivered: " + evaluate(view, "JSON.stringify(window.__pingclashFrame)"),
            rendered.optBoolean("active"),
        )
        assertEquals("three", rendered.getString("engine"))
        assertTrue(rendered.getInt("frames") > 0)
        assertEquals(.42, rendered.getDouble("roughness"), .001)
        assertEquals(1.0, rendered.getDouble("clearcoat"), .001)
        assertEquals(.16, rendered.getDouble("metalRoughness"), .001)
        await { control.ready }
        compose.waitForIdle()
        assertEquals(
            "Canvas size",
            "true",
            evaluate(view, "document.querySelector('canvas').clientHeight>100"),
        )
        captureSyntheticFixture(
            compose,
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            "lanyard-three-front.png",
        )
        compose.onNodeWithTag("vpn-power").performClick()
        compose.waitForIdle()
        await { evaluate(view, "window.__pingclashFrame.back") == "true" }
        compose.runOnIdle { phase.value = "established" }
        compose.waitForIdle()
        await { evaluate(view, "window.__pingclashFrame.phase") == "\"established\"" }
        compose.runOnIdle { motion.value = false }
        compose.waitForIdle()
        await { evaluate(view, "window.__pingclashFrame.motion") == "false" }
        await {
            status(view).optJSONObject("pose")?.optDouble("normalZ", 1.0)?.let { it < -.85 } == true
        }
        Thread.sleep(1200)
        captureSyntheticFixture(
            compose,
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            "lanyard-three-back.png",
        )
        assertDragAndResumeKeepBackFace(view) { motion.value = true }
        compose.onNodeWithTag("vpn-power").performClick()
        compose.waitForIdle()
        await { evaluate(view, "window.__pingclashFrame.back") == "false" }
        await { status(view).getJSONObject("pose").getDouble("normalZ") > .6 }
        compose.runOnIdle {
            motion.value = true
            phase.value = "idle"
        }
        compose.waitForIdle()
        val beforePause = status(view).getInt("frames")
        compose.runOnUiThread { control.visible(false) }
        Thread.sleep(200)
        compose.runOnUiThread { control.visible(true) }
        await { status(view).optBoolean("active") && status(view).getInt("frames") > beforePause }
        assertFalse(VpnRuntime.state.value.active)
    }

    @Test
    fun tabSwitchKeepsRendererAndAcknowledgesTheCurrentFace() {
        val page = mutableStateOf(0)
        val phase = mutableStateOf("idle")
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    ScenicFrame(page.value, "none", true, { page.value = it }) {
                        val renderer = LocalLanyardThree.current
                        SideEffect { control = requireNotNull(renderer) }
                        androidx.compose.animation.AnimatedContent(
                            page.value,
                            label = "fixture-page",
                        ) { selected ->
                            if (selected == 0)
                                Column(
                                    Modifier.fillMaxSize()
                                        .padding(horizontal = 24.dp, vertical = 100.dp)
                                ) {
                                    LanyardPowerControl(phase.value, true, "Fixture action") {
                                        phase.value = "idle"
                                    }
                                }
                            else androidx.compose.material3.Text("Synthetic second page")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = renderer()
        await { control.ready }
        repeat(3) {
            compose.onNodeWithTag("nav-1").performClick()
            compose.waitForIdle()
            assertTrue("Tab switch destroyed the offline renderer", view === renderer())
            compose.runOnIdle { phase.value = "established" }
            compose.onNodeWithTag("nav-0").performClick()
            compose.waitForIdle()
            await { control.ready && status(view).optString("paintedPhase") == "established" }
            await { status(view).getJSONObject("pose").getDouble("normalZ") < -.6 }
            compose.onNodeWithTag("vpn-power").performClick()
            compose.waitForIdle()
            await { control.ready && status(view).optString("paintedPhase") == "idle" }
            await { status(view).getJSONObject("pose").getDouble("normalZ") > .6 }
        }
        compose.onNodeWithTag("vpn-power").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(120f, 90f), 180)
            up()
        }
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("nav-0").performClick()
        compose.waitForIdle()
        await { control.ready && status(view).optBoolean("active") }
        assertTrue(view === renderer())
        assertFalse(control.dragging)
        assertFalse(VpnRuntime.state.value.active)
    }

    @Test
    fun returningHomeKeepsThePaintedFaceAndRecoversOneDroppedResumeWithoutReplaying() {
        val page = mutableStateOf(0)
        val phase = mutableStateOf("established")
        val visibility = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(LocalMotionAllowed provides true) {
                        ScenicFrame(page.value, "none", true, { page.value = it }) {
                            val renderer = LocalLanyardThree.current
                            SideEffect {
                                control = requireNotNull(renderer)
                                if (page.value == 0) visibility.add(control.displayReady)
                            }
                            androidx.compose.animation.AnimatedContent(
                                page.value,
                                label = "quiet-page",
                            ) { selected ->
                                if (selected == 0)
                                    Column(
                                        Modifier.fillMaxSize()
                                            .padding(horizontal = 24.dp, vertical = 100.dp)
                                    ) {
                                        LanyardPowerControl(phase.value, true, "Fixture action") {
                                            phase.value =
                                                if (phase.value == "idle") "established" else "idle"
                                        }
                                    }
                                else androidx.compose.material3.Text("Synthetic second page")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = renderer()
        await { control.ready && status(view).getJSONObject("pose").getDouble("normalZ") < -.85 }
        visibility.clear()
        repeat(4) {
            compose.onNodeWithTag("nav-1").performClick()
            compose.waitForIdle()
            assertTrue(evaluate(view, "window.__pingclashFrame.height").toDouble() > 0)
            compose.onNodeWithTag("nav-0").performClick()
            compose.waitForIdle()
            repeat(8) {
                assertTrue(
                    "Return replayed the front face",
                    status(view).getJSONObject("pose").getDouble("normalZ") < -.6,
                )
                Thread.sleep(30)
            }
            await { control.ready }
            assertTrue(view === renderer())
        }
        assertTrue("Normal navigation hid the already-painted scene", visibility.all { it })
        assertEquals("Navigation added a flip impulse", 0, status(view).getInt("flips"))
        assertDroppedResumeRecovers(view)
        compose.onNodeWithTag("nav-1").performClick()
        compose.waitForIdle()
        compose.runOnIdle { phase.value = "idle" }
        compose.onNodeWithTag("nav-0").performClick()
        compose.waitForIdle()
        await { control.ready && status(view).getJSONObject("pose").getDouble("normalZ") > .85 }
        assertEquals(0, status(view).getInt("flips"))
        compose.onNodeWithTag("vpn-power").performClick()
        compose.waitForIdle()
        await {
            status(view).getInt("flips") == 1 &&
                status(view).getJSONObject("pose").getDouble("normalZ") < -.6
        }
        assertFalse(VpnRuntime.state.value.active)
    }

    private fun assertDroppedResumeRecovers(view: WebView) {
        compose.runOnUiThread { control.visible(false) }
        Thread.sleep(120)
        evaluate(
            view,
            """window.__dispatchSaved=window.dispatchEvent;window.__dropResume=true;
            window.dispatchEvent=function(e){if(e.type==='pingclash-frame'&&window.__dropResume){
            window.__dropResume=false;return true;}return window.__dispatchSaved.call(this,e);};""",
        )
        compose.runOnUiThread { control.visible(true) }
        await { control.ready && status(view).optBoolean("active") }
        assertTrue(view === renderer())
        assertEquals(0, status(view).getInt("flips"))
        assertTrue(status(view).getJSONObject("pose").getDouble("normalZ") < -.6)
        evaluate(view, "window.dispatchEvent=window.__dispatchSaved;delete window.__dispatchSaved")
    }

    private fun assertDragAndResumeKeepBackFace(view: WebView, resumeMotion: () -> Unit) {
        compose.runOnIdle { resumeMotion() }
        compose.waitForIdle()
        await { evaluate(view, "window.__pingclashFrame.motion") == "true" }
        Thread.sleep(1800)
        assertTrue(
            "Resuming motion flipped an established VPN back to the front",
            status(view).getJSONObject("pose").getDouble("normalZ") < -.6,
        )
        val before = status(view).getJSONObject("pose")
        val naturalRest = before.getDouble("rest")
        compose.onNodeWithTag("vpn-power").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(-200f, 250f), 180)
        }
        compose.waitForIdle()
        Thread.sleep(250)
        assertTrue(
            "Screen-space drag frame=" +
                evaluate(view, "JSON.stringify(window.__pingclashFrame)") +
                " pose=" +
                status(view),
            evaluate(view, "window.__pingclashFrame.dx").toDouble() < -1,
        )
        await {
            val pose = status(view).getJSONObject("pose")
            pose.optBoolean("held") && pose.getDouble("x") < before.getDouble("x") - .01
        }
        val held = status(view).getJSONObject("pose")
        assertTrue("Back-face left drag moved right", held.getDouble("x") < before.getDouble("x"))
        assertTrue("Down drag moved upward", held.getDouble("y") < before.getDouble("y"))
        assertEquals(
            "Pull changed the strap's natural length",
            naturalRest,
            held.getDouble("rest"),
            .0001,
        )
        Thread.sleep(350)
        val stationary = status(view).getJSONObject("pose")
        assertStationaryCoordinates(held, stationary)
        compose.onNodeWithTag("vpn-power").performTouchInput { up() }
        Thread.sleep(3500)
        await { status(view).getJSONObject("pose").getDouble("normalZ") < -.6 }
        assertEquals(
            "Drag must not change VPN phase",
            "\"established\"",
            evaluate(view, "window.__pingclashFrame.phase"),
        )
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        await {
            status(view).optBoolean("active") &&
                status(view).getJSONObject("pose").getDouble("normalZ") < -.6
        }
    }

    @Test
    fun entryWaitsForPresentationFallsOnceAndSettlesBeforeSleeping() {
        fixture { LanyardPowerControl("idle", true, "Fixture action") {} }
        val view = renderer()
        await { control.ready }
        await { status(view).optJSONObject("pose")?.optString("stage") == "idle" }
        assertEquals(1, status(view).getJSONObject("pose").getInt("entries"))
        compose
            .onNodeWithTag("lanyard-minimal-fallback", useUnmergedTree = true)
            .assertDoesNotExist()
        Thread.sleep(3000)
        val sleeping = status(view).getInt("frames")
        Thread.sleep(350)
        assertEquals("Settled scene keeps rendering", sleeping, status(view).getInt("frames"))
        compose.runOnUiThread { control.visible(false) }
        compose.runOnUiThread { control.visible(true) }
        await { control.ready }
        assertEquals(
            "Resume replayed entry",
            1,
            status(view).getJSONObject("pose").getInt("entries"),
        )
        assertEquals("idle", status(view).getJSONObject("pose").getString("stage"))
    }

    @Test
    fun rendererCannotReadLocalFilesNavigateOrConnectToRemoteHosts() {
        fixture { LanyardPowerControl("idle", true, "Fixture action") {} }
        val view = renderer()
        await { status(view).optBoolean("ready") }
        compose.runOnUiThread {
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertFalse(view.settings.domStorageEnabled)
            assertTrue(view.settings.blockNetworkLoads)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
            val client = OfflineLanyardClient(compose.activity.assets, {}, {})
            assertTrue(client.permits(Uri.parse(LANYARD_PAGE)))
            for (url in
                listOf(
                    "http://appassets.androidplatform.net/lanyard/index.html",
                    "$LANYARD_PAGE?token=CANARY-SUB-TOKEN-1234",
                    "$LANYARD_PAGE#fragment",
                    "https://example.invalid/lanyard/index.html",
                    "file:///data/data/dev.pingclash.app/",
                    "content://dev.pingclash.app/private",
                    "https://appassets.androidplatform.net/lanyard/../private",
                    "https://user@appassets.androidplatform.net/lanyard/index.html",
                )) {
                assertFalse(client.permits(Uri.parse(url)))
            }
        }
        assertEquals("\"undefined\"", evaluate(view, "typeof window.Android"))
        evaluate(
            view,
            "window.__blocked=false;fetch('https://example.invalid/').catch(()=>window.__blocked=true)",
        )
        await { evaluate(view, "window.__blocked") == "true" }
        evaluate(view, "window.PingClashScene.frame({active:false})")
        Thread.sleep(250)
        val stopped = status(view).getInt("frames")
        Thread.sleep(250)
        assertEquals(stopped, status(view).getInt("frames"))
        evaluate(view, "window.PingClashScene.frame({active:true})")
        await { status(view).getInt("frames") > stopped }
    }

    private fun fixture(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("dark") {
                    ScenicFrame(0, "none", true, {}) {
                        val renderer = LocalLanyardThree.current
                        SideEffect { control = requireNotNull(renderer) }
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 100.dp)
                        ) {
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun renderer(): WebView {
        var result: WebView? = null
        compose.runOnUiThread { result = find(compose.activity.window.decorView) }
        return requireNotNull(result)
    }

    private fun find(view: View): WebView? {
        return when {
            view is WebView && view.tag == "lanyard-offline-webview" -> view
            view is ViewGroup ->
                (0 until view.childCount)
                    .asSequence()
                    .mapNotNull { find(view.getChildAt(it)) }
                    .firstOrNull()
            else -> null
        }
    }

    private fun status(view: WebView): JSONObject =
        JSONObject(
            JSONTokener(evaluate(view, "JSON.stringify(window.PingClashScene?.status()||{})"))
                .nextValue()
                .toString()
        )

    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) {
                result = it
                latch.countDown()
            }
        }
        assertTrue("Offline renderer JavaScript callback", latch.await(30, TimeUnit.SECONDS))
        return result
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        val details =
            runCatching {
                    val view = renderer()
                    "ready=${control.ready}; unavailable=${control.unavailable}; " +
                        "status=${status(view)}; frame=" +
                        evaluate(view, "JSON.stringify(window.__pingclashFrame)")
                }
                .getOrDefault("renderer unavailable")
        assertTrue("Offline Three renderer reached the expected state: $details", predicate())
    }
}

private fun assertStationaryCoordinates(held: JSONObject, stationary: JSONObject) {
    for (axis in listOf("x", "y")) {
        assertEquals(
            "Stationary finger drift on $axis",
            held.getDouble(axis),
            stationary.getDouble(axis),
            .001,
        )
    }
}
