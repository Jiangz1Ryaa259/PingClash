// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The AGSL scene compiles, accepts every uniform the app sets, and renders a bright frame. */
@RunWith(AndroidJUnit4::class)
class OceanShaderTest {
    @Test
    fun oceanCloudsAndNightRender() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val shader = RuntimeShader(OCEAN_SHADER)
        val out =
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        for ((name, night, clouds) in
            listOf(Triple("ocean", 0f, 0f), Triple("clouds", 0f, 1f), Triple("night", 1f, 0f))) {
            shader.setFloatUniform("resolution", 540f, 1170f)
            shader.setFloatUniform("clock", 20f)
            shader.setFloatUniform("night", night)
            shader.setFloatUniform("cloudOnly", clouds)
            shader.setFloatUniform("dive", 0f)
            // AGSL needs the GPU: record a picture and rasterise it as a hardware bitmap.
            val picture = Picture()
            picture
                .beginRecording(540, 1170)
                .drawRect(0f, 0f, 540f, 1170f, Paint().apply { this.shader = shader })
            picture.endRecording()
            val bitmap =
                Bitmap.createBitmap(picture, 540, 1170, Bitmap.Config.HARDWARE)
                    .copy(Bitmap.Config.ARGB_8888, false)
            out?.let { dir ->
                File(dir, "scene-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val sky = bitmap.getPixel(270, 120)
            val brightness = (sky shr 16 and 0xFF) + (sky shr 8 and 0xFF) + (sky and 0xFF)
            assertTrue(
                "$name sky too dark: $brightness",
                if (night > 0f) brightness > 20 else brightness > 450,
            )
        }
    }
}
