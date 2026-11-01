// Synthetic palette, timing and screenshot fixtures use deliberate test values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.diagnostics.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OceanDesignTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun textAndSwitchContrastHoldOverEveryWavePalette() {
        for (dark in listOf(false, true)) {
            val scheme = oceanColors(dark)
            val semantic = listOf(successColor(dark), warningColor(dark))
            val texts =
                listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.error) +
                    semantic
            val backgrounds = TrailTone.entries.flatMap { oceanWaveColors(dark, it) }
            assertGlassContrast(scheme, backgrounds, texts)
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5)
            assertTrue(
                "Switch off outline is not visible",
                contrast(scheme.outline, scheme.surfaceVariant) >= 3,
            )
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 3)
            for (sample in 0..20) {
                val background = lerp(scheme.primary, scheme.primaryContainer, sample / 20f)
                assertTrue(
                    "Connection label loses contrast while animating",
                    contrast(
                        controlForeground(scheme.onPrimaryContainer, background),
                        background,
                    ) >= 4.5,
                )
            }
        }
    }

    @Test
    fun diagnosticPathNeverInventsSuccessOrBlamesAnUnknownTimeoutStage() {
        val policy =
            step(Models.Stage.POLICY, Models.State.SKIPPED, Models.Code.NODE_CERTIFICATE_UNVERIFIED)
        val success = step(Models.Stage.PROXY, Models.State.PASSED, Models.Code.PROXY_OK)
        val timeout = step(Models.Stage.PROXY, Models.State.FAILED, Models.Code.PROXY_TIMEOUT)
        assertTrue(diagnosticTrail(report(policy), "node").all { it == TrailTone.PENDING })
        assertTrue(diagnosticTrail(report(success), "other").all { it == TrailTone.PENDING })
        assertEquals(
            listOf(TrailTone.PENDING, TrailTone.PENDING, TrailTone.PASSED, TrailTone.PASSED),
            diagnosticTrail(report(success), "node"),
        )
        val waiting = diagnosticTrail(report(timeout), "node")
        assertEquals(TrailTone.INCOMPLETE, waiting[2])
        assertEquals(TrailTone.INCOMPLETE, waiting[3])
        assertFalse(TrailTone.FAILED in waiting)
        val refused = step(Models.Stage.TCP, Models.State.FAILED, Models.Code.TCP_REFUSED)
        assertEquals(TrailTone.FAILED, diagnosticTrail(report(refused, timeout), "node")[2])
        val target = step(Models.Stage.PROXY, Models.State.FAILED, Models.Code.TARGET_CERTIFICATE)
        assertEquals(TrailTone.PASSED, diagnosticTrail(report(target), "node")[2])
        assertEquals(TrailTone.FAILED, diagnosticTrail(report(target), "node")[3])
        val stale = step(Models.Stage.PROXY, Models.State.STALE, Models.Code.NETWORK_CHANGED)
        assertTrue(diagnosticTrail(report(success, stale), "node").all { it == TrailTone.STALE })
        assertFalse(
            TrailTone.PASSED in diagnosticTrail(report(success).copy(running = true), "node")
        )
        val impossible = step(Models.Stage.PROXY, Models.State.FAILED, Models.Code.PROXY_OK)
        assertEquals(TrailTone.INCOMPLETE, diagnosticTrail(report(impossible), "node")[3])
    }

    @Test
    fun systemMotionAndEncryptedReduceEffectsPreferenceStopDecorativeAnimation() {
        val before = app.repository.data.value.client
        val originalScale =
            device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            app.repository.appearance("zh", "light", dynamicColor = false, reduceEffects = false)
            compose.runOnUiThread {
                compose.activity.setContent {
                    val data by app.repository.data.collectAsState()
                    val motion = rememberMotionAllowed(data.client.reduceEffects)
                    CompositionLocalProvider(LocalMotionAllowed provides motion) {
                        PingClashTheme("light") {
                            OceanBackdrop(TrailTone.PENDING, true, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            compose.waitUntil(5000) { effectRunning() }
            app.repository.appearance(
                "zh",
                "light",
                reduceEffects = true,
                dynamicColor = true,
                scenery = ScenePreferenceChange(scene = "clouds", reduceTransparency = true),
            )
            compose.waitUntil(5000) { !effectRunning() }
            val restored = ProfileRepository(app).data.value.client
            assertTrue(restored.reduceEffects)
            assertTrue(restored.dynamicColor)
            assertEquals("clouds", restored.scene)
            assertTrue(restored.reduceTransparency)
            device.executeShellCommand("settings put global animator_duration_scale 0")
            app.repository.appearance("zh", "light", reduceEffects = false)
            compose.waitUntil(5000) { !effectRunning() }
        } finally {
            app.repository.appearance(
                before.language,
                before.theme,
                before.dynamicColor,
                before.reduceEffects,
                ScenePreferenceChange(before.scene, before.reduceTransparency),
            )
            val command =
                if (originalScale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $originalScale"
            device.executeShellCommand(command)
        }
    }

    @Test
    fun lightDarkAndLargeFontGalleryAndWholeRowSwitchStayAccessible() {
        gallery("light", 1f)
        compose.onNodeWithTag("vpn-power").assertIsDisplayed()
        captureSyntheticFixture(compose, device, "ocean-home-light.png")
        gallery("dark", 1f)
        compose.onNodeWithTag("vpn-power").assertIsDisplayed()
        captureSyntheticFixture(compose, device, "ocean-home-dark.png")
        gallery("light", 1.8f)
        compose.onNodeWithTag("vpn-power").assertIsDisplayed()
        captureSyntheticFixture(compose, device, "ocean-home-large.png")
        compose.runOnUiThread { compose.activity.setContent { ControlFixture() } }
        val toggle = compose.onNodeWithTag("switch-Synthetic switch")
        assertEquals(Role.Switch, toggle.fetchSemanticsNode().config[SemanticsProperties.Role])
        toggle.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("mode-rule").assertIsSelected()
        compose.onNodeWithTag("mode-global").performClick().assertIsSelected()
        assertFalse("A visual fixture started a real VPN", VpnRuntime.state.value.active)
    }

    private fun effectRunning() =
        compose.onNodeWithTag("ocean-backdrop").fetchSemanticsNode().config[DecorativeEffectRunning]

    private fun report(vararg steps: Models.Step) =
        DiagnosticUiState(
            nodeId = "node",
            report = Models.Report("fixture", "fixture", "Wi-Fi", steps.toList(), 84),
        )

    private fun gallery(mode: String, scale: Float) {
        var foreground = Color.Unspecified
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale),
                    LocalMotionAllowed provides false,
                ) {
                    PingClashTheme(mode) {
                        val contentColor = LocalContentColor.current
                        SideEffect { foreground = contentColor }
                        DashboardGallery()
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(oceanColors(mode == "dark").onBackground, foreground)
    }

    @Composable
    private fun ControlFixture() {
        var value by remember { mutableStateOf(false) }
        var mode by remember { mutableStateOf("rule") }
        PingClashTheme("light") {
            Column {
                SettingSwitch("Synthetic switch", value, true) { value = it }
                ModeSegments(mode, true) { mode = it }
            }
        }
    }

    @Composable
    private fun DashboardGallery() {
        val node = LocalNode("node", "东京 · Tokyo 02", "anytls", "{}")
        val profile = LocalProfile("profile", "示例配置", null, listOf(node), 0, false)
        val data =
            AppData(
                profiles = listOf(profile),
                selectedId = node.id,
                activeProfileId = profile.id,
                client =
                    ClientSettings(showTraffic = false, showService = false, showMemory = false),
            )
        val tests =
            ProxyTestState(
                values = mapOf(delayKey(profile.id, node.name) to ProxyDelay("passed", 84))
            )
        val vpn =
            VpnUiState(
                "established",
                uploadSpeed = 2048,
                downloadSpeed = 188000,
                history = List(60) { SpeedSample((it % 8 + 1) * 1024L, (it % 12 + 1) * 14000L) },
            )
        val diagnosis =
            report(
                step(Models.Stage.NETWORK, Models.State.PASSED, Models.Code.NETWORK_READY),
                step(Models.Stage.DNS, Models.State.PASSED, Models.Code.DNS_OK),
                step(Models.Stage.PROXY, Models.State.PASSED, Models.Code.PROXY_OK),
            )
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        SideEffect {
            androidx.core.view.WindowCompat.getInsetsController(
                    compose.activity.window,
                    compose.activity.window.decorView,
                )
                .apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
        }
        ScenicFrame(0, "ocean", android.os.Build.VERSION.SDK_INT == 31, {}) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                DashboardScreen(
                    DashboardState(data, vpn, diagnosis, tests, true, false),
                    DashboardActions({}, {}, {}, {}),
                )
            }
        }
    }
}

private fun contrast(text: Color, background: Color) =
    ColorUtils.calculateContrast(text.toArgb(), background.toArgb())

private fun step(stage: Models.Stage, state: Models.State, code: Models.Code) =
    Models.Step(stage, state, code, 84, "", 0)

private fun assertGlassContrast(scheme: ColorScheme, backgrounds: List<Color>, texts: List<Color>) {
    val dark = scheme.background.luminance() < .5f
    for (background in backgrounds + listOf(Color.Black, Color.White)) {
        val panel = scheme.surface.copy(alpha = CONNECTION_TINT_ALPHA).compositeOver(background)
        texts.forEach {
            assertTrue("Connection panel text contrast below 4.5:1", contrast(it, panel) >= 4.5)
        }
        val glass = navigationGlassStyle(dark).tint.compositeOver(background)
        val labelSurface = navReadabilityTint(dark).compositeOver(glass)
        val selectedSurface =
            navAccentColor(dark).copy(alpha = if (dark) .12f else .06f).compositeOver(labelSurface)
        assertTrue(
            "Navigation text contrast below 4.5:1",
            contrast(navLabelColor(dark), labelSurface) >= 4.5,
        )
        assertTrue(
            "Selected navigation text contrast below 4.5:1",
            contrast(navAccentColor(dark), selectedSurface) >= 4.5,
        )
    }
}
