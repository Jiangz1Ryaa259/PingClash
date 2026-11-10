// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomBackgroundUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun storedPictureIsDrawnBehindPagesAndCanBeRemovedFromAppearance() {
        withPicture(900, 1600) { app ->
            // Frame it: Fit mode with white around the picture, then Done saves the framing.
            visibleSettingsControl(compose, "background-edit").performClick()
            compose.onNodeWithTag("background-editor").assertExists()
            compose.onNodeWithTag("background-mode-fit").performClick()
            compose.onNodeWithTag("background-fill-1").performClick()
            compose.onNodeWithTag("background-done").performClick()
            compose.waitUntil(10_000) {
                BackgroundFrames.load(app).let { it.fit && it.fill == 0xFFFFFFFF.toInt() }
            }
            compose.onNodeWithTag("background-editor").assertDoesNotExist()

            visibleSettingsControl(compose, "background-change")
            visibleSettingsControl(compose, "background-remove").performClick()
            compose.waitUntil(10_000) { app.repository.data.value.client.scene == "ocean" }
            assertFalse(CustomBackground.exists(app))
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("custom-background", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            assertEquals("ocean", app.repository.data.value.client.scene)
        }
    }

    @Test
    fun cancellingANewlyPickedPictureKeepsTheCurrentBackground() {
        withPicture(900, 1600) { app ->
            val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            val before = CustomBackground.file(app).readBytes()
            val other = File(compose.activity.cacheDir, "background-other.png")
            try {
                val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(40, 90, 200))
                other.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()

                // Cancel: the picked picture is thrown away and the old one stays in use.
                model.importBackground(Uri.fromFile(other))
                compose.waitUntil(10_000) { model.backgroundStaged.value != null }
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodesWithTag("background-editor")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("background-cancel").performClick()
                compose.waitUntil(10_000) { CustomBackground.load(app, pending = true) == null }
                compose.onNodeWithTag("background-editor").assertDoesNotExist()
                assertArrayEquals(before, CustomBackground.file(app).readBytes())
                assertEquals("image", app.repository.data.value.client.scene)

                // Done: the picked picture replaces it.
                model.importBackground(Uri.fromFile(other))
                compose.waitUntil(10_000) { model.backgroundStaged.value != null }
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodesWithTag("background-editor")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("background-done").performClick()
                compose.waitUntil(10_000) {
                    model.backgroundStaged.value == null &&
                        !CustomBackground.file(app).readBytes().contentEquals(before)
                }
            } finally {
                other.delete()
            }
        }
    }

    @Test
    fun pinchAndDragReframeThePictureAndFitTakesAnAddedColour() {
        withPicture(1600, 900) { app ->
            visibleSettingsControl(compose, "background-edit").performClick()
            val frame = compose.onNodeWithTag("background-frame")
            frame.performTouchInput {
                pinch(
                    center - Offset(40f, 0f),
                    center - Offset(160f, 0f),
                    center + Offset(40f, 0f),
                    center + Offset(160f, 0f),
                )
            }
            frame.performTouchInput {
                swipeLeft(startX = centerX + width / 4f, endX = centerX - width / 4f)
            }
            compose.onNodeWithTag("background-done").performClick()
            // Every event builds on the last: the picture ends well zoomed and moved left.
            compose.waitUntil(10_000) {
                BackgroundFrames.load(app).let { !it.fit && it.zoom > 2f && it.x < -.2f }
            }

            val added = 0xFFCCE5FF.toInt()
            visibleSettingsControl(compose, "background-edit").performClick()
            compose.onNodeWithTag("background-mode-fit").performClick()
            compose.onNodeWithTag("background-fill-add").performClick()
            compose.onNodeWithTag("solid-hex").performTextReplacement("#CCE5FF")
            compose.onNodeWithTag("solid-color-save").performScrollTo().performClick()
            compose.onNodeWithTag("background-fill-6").assertIsSelected()
            compose.onNodeWithTag("background-done").performClick()
            compose.waitUntil(10_000) {
                BackgroundFrames.load(app).let {
                    it.fit && it.fill == added && it.custom == listOf(added)
                }
            }
        }
    }

    /** Stores a [width] x [height] picture, shows it and opens the appearance page. */
    private fun withPicture(width: Int, height: Int, block: (PingClashApplication) -> Unit) {
        val app = compose.activity.application as PingClashApplication
        val before = app.repository.data.value.client
        val source = File(compose.activity.cacheDir, "background-ui.png")
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(200, 90, 60))
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            CustomBackground.commit(app, CustomBackground.stage(app, Uri.fromFile(source)))
            app.repository.appearance(
                before.language,
                before.theme,
                scenery = ScenePreferenceChange(scene = "image"),
            )
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("custom-background", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("nav-3").performClick()
            visibleSettingsControl(compose, "settings-appearance").performClick()
            compose.onNodeWithTag("appearance-preview").assertDoesNotExist()
            compose.onNodeWithTag("scene-ocean").assertExists()
            block(app)
        } finally {
            source.delete()
            CustomBackground.clear(app)
            app.repository.appearance(
                before.language,
                before.theme,
                scenery = ScenePreferenceChange(scene = before.scene),
            )
        }
    }
}
