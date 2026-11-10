// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What sits directly on the background has to read on that background, whatever the theme. */
@RunWith(AndroidJUnit4::class)
class PageInkTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val app
        get() = compose.activity.application as PingClashApplication

    private fun titleLuminance(): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("page-title-home")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().layoutInput.style.color.luminance()
    }

    private fun darkBarIcons() =
        WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            .isAppearanceLightStatusBars

    private fun show(scene: String, theme: String) {
        val client = app.repository.data.value.client
        compose.runOnUiThread {
            app.repository.appearance(
                client.language,
                theme,
                scenery = ScenePreferenceChange(scene = scene),
            )
        }
        compose.waitUntil(WAIT) { app.repository.data.value.client.scene == scene }
        compose.waitForIdle()
    }

    @Test
    fun titlesAndBarIconsFollowTheBackgroundInEveryTheme() {
        val before = app.repository.data.value.client
        try {
            // The live scene follows the theme; the older photos are dark in both.
            val live = android.os.Build.VERSION.SDK_INT >= 33
            for (theme in listOf("light", "dark")) {
                for (scene in listOf("ocean", "clouds")) {
                    show(scene, theme)
                    val darkText = live && theme == "light"
                    assertEquals("$scene/$theme title", darkText, titleLuminance() < DIM)
                    assertEquals("$scene/$theme icons", darkText, darkBarIcons())
                }
            }
            // A solid background keeps the light theme's dark text and icons.
            show("none", "light")
            assertTrue("solid title", titleLuminance() < DIM)
            assertTrue("solid icons", darkBarIcons())
        } finally {
            show(before.scene, before.theme)
        }
    }

    @Test
    fun aPictureGetsTheInkThatReadsOnIt() {
        fun filled(color: Int) =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

        val light = Color.White.copy(alpha = .32f)
        val dim = Color.Black.copy(alpha = .4f)
        val night = pictureLuminance(filled(android.graphics.Color.BLACK), dim)
        val snow = pictureLuminance(filled(android.graphics.Color.WHITE), light)
        assertTrue(night < .01f)
        assertTrue(snow > .9f)
        assertTrue(requireNotNull(sceneInk("image", night)).text.luminance() > BRIGHT)
        assertTrue(requireNotNull(sceneInk("image", snow)).text.luminance() < DIM)
        // The theme's own colours apply on a solid background or before a picture has loaded.
        assertNull(sceneInk("none", null))
        assertNull(sceneInk("image", null))
        assertNull(sceneInk("ocean", null, liveScene = true))
        assertTrue(
            requireNotNull(sceneInk("clouds", null, liveScene = false)).text.luminance() > BRIGHT
        )
    }

    private companion object {
        const val WAIT = 10_000L
        const val BRIGHT = .8f
        const val DIM = .2f
    }
}
