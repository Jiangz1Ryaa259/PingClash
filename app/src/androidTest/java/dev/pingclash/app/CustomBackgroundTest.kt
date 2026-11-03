// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomBackgroundTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "background-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        CustomBackground.clear(context)
        scratch.deleteRecursively()
    }

    @Test
    fun importDownscalesReencodesAndDropsEmbeddedMetadata() {
        val photo = jpeg(3200, 1800)
        // A comment segment stands in for camera metadata such as a GPS location.
        val marked = withComment(photo, "CANARY-GPS-48.8584N-2.2945E")
        CustomBackground.commit(context, CustomBackground.stage(context, uri("photo.jpg", marked)))
        val stored = CustomBackground.file(context).readBytes()
        assertEquals("RIFF", String(stored, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(stored, 8, 4, Charsets.US_ASCII))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("CANARY-GPS"))
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(stored, 0, stored.size, size)
        assertEquals(2400, maxOf(size.outWidth, size.outHeight))
        assertTrue(CustomBackground.exists(context))
    }

    @Test
    fun nonImagesOversizedAndTruncatedFilesAreRejectedBeforeReplacingThePicture() {
        CustomBackground.commit(
            context,
            CustomBackground.stage(context, uri("first.jpg", jpeg(800, 600))),
        )
        val kept = CustomBackground.file(context).readBytes()

        assertRejected(BackgroundRejection.NOT_AN_IMAGE, "notes.png", "not an image".toByteArray())
        assertRejected(BackgroundRejection.TOO_LARGE, "wide.png", png(12_001, 2))
        assertRejected(BackgroundRejection.TOO_LARGE, "huge.png", ByteArray(21 * 1024 * 1024))
        val photo = jpeg(1200, 900)
        assertRejected(BackgroundRejection.NOT_AN_IMAGE, "cut.jpg", photo.copyOf(photo.size / 3))

        assertArrayEquals(kept, CustomBackground.file(context).readBytes())
    }

    @Test
    fun framingKeepsFillCoveredLetsFitShrinkAndRejectsBadStoredValues() {
        val square = androidx.compose.ui.unit.IntSize(1000, 1000)
        // A square picture in a 1:2 screen: Fill may never reveal an edge.
        val fill =
            BackgroundFrame(fit = false, zoom = .5f, x = .9f, y = .4f).clamped(square, 100f, 200f)
        assertEquals(1f, fill.zoom, .001f)
        assertEquals(.5f, fill.x, .001f)
        assertEquals(0f, fill.y, .001f)
        // Fit may shrink it down to fully visible, with the rest in the fill colour.
        val fit = BackgroundFrame(fit = true, zoom = .2f, x = .9f).clamped(square, 100f, 200f)
        assertEquals(.5f, fit.zoom, .001f)
        assertEquals(.5f, fit.x, .001f)

        assertEquals(BackgroundFrame(), BackgroundFrame.read("not json"))
        val wild = BackgroundFrame.read("""{"zoom":99,"x":-7,"fill":0}""")
        assertEquals(BackgroundFrame.MAX_ZOOM, wild.zoom, .001f)
        assertEquals(-1f, wild.x, .001f)
        assertEquals(0xFF000000.toInt(), wild.fill)
        // Added fill colours survive a round trip, opaque, distinct and bounded.
        val colours = BackgroundFrame(custom = listOf(0xFFCCE5FF.toInt(), 0xFF336699.toInt()))
        assertEquals(colours, BackgroundFrame.read(colours.json()))
        val many = (1..40).joinToString(",", "[", "]")
        val read = BackgroundFrame.read("""{"custom":$many}""")
        assertEquals(BackgroundFrame.MAX_CUSTOM, read.custom.size)
        assertTrue(read.custom.all { it ushr 24 == 0xFF })
    }

    @Test
    fun framingNeverFailsWhenThePictureExactlySpansTheScreen() {
        // Rounding once made the Fill margin a hair below zero and drawing threw.
        val pictures = listOf(3024 to 4032, 4032 to 3024, 1179 to 2556, 1080 to 2400, 2400 to 1600)
        val screens =
            listOf(
                1080f to 2400f,
                1206f to 2622f,
                393f to 852f,
                1080f to 2341.3333f,
                412f to 915.5f,
            )
        for ((w, h) in pictures) for ((bw, bh) in screens) for (fit in listOf(false, true)) {
            val frame =
                BackgroundFrame(fit = fit, zoom = 1f, x = .3f, y = -.3f)
                    .clamped(androidx.compose.ui.unit.IntSize(w, h), bw, bh)
            assertTrue("$w x $h in $bw x $bh", frame.zoom.isFinite() && frame.x.isFinite())
        }
    }

    @Test
    fun importStartsFilledAndOffersThePicturesEdgeColour() {
        CustomBackground.commit(
            context,
            CustomBackground.stage(context, uri("photo.png", png(640, 960))),
        )
        val frame = BackgroundFrames.load(context)
        assertFalse(frame.fit)
        assertEquals(Color.rgb(40, 120, 160), frame.auto)
        assertEquals(frame.auto, frame.fill)
    }

    @Test
    fun clearingRemovesTheStoredCopy() {
        CustomBackground.commit(
            context,
            CustomBackground.stage(context, uri("photo.jpg", jpeg(640, 480))),
        )
        val revision = CustomBackground.revision.intValue
        CustomBackground.clear(context)
        assertFalse(CustomBackground.exists(context))
        assertTrue(CustomBackground.revision.intValue > revision)
        assertEquals(null, CustomBackground.load(context))
    }

    private fun assertRejected(expected: BackgroundRejection, name: String, bytes: ByteArray) {
        val failure = runCatching {
            CustomBackground.commit(context, CustomBackground.stage(context, uri(name, bytes)))
        }
        val reason = (failure.exceptionOrNull() as? BackgroundImportException)?.reason
        assertEquals(name, expected, reason)
    }

    private fun uri(name: String, bytes: ByteArray): Uri =
        Uri.fromFile(File(scratch, name).apply { writeBytes(bytes) })

    private fun jpeg(width: Int, height: Int) = encode(width, height, Bitmap.CompressFormat.JPEG)

    private fun png(width: Int, height: Int) = encode(width, height, Bitmap.CompressFormat.PNG)

    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 120, 160))
            return ByteArrayOutputStream().use {
                check(bitmap.compress(format, 90, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Inserts a JPEG COM segment right after the start-of-image marker. */
    private fun withComment(jpeg: ByteArray, text: String): ByteArray {
        val body = text.toByteArray(Charsets.US_ASCII)
        val length = body.size + 2
        val segment =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), (length shr 8).toByte(), length.toByte()) +
                body
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }
}
