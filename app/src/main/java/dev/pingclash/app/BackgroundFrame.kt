// SPDX-License-Identifier: GPL-3.0-only
// Zoom and offset bounds are the documented framing limits.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the stored picture fills the screen. [zoom] is relative to the size that just covers the
 * screen; [x] and [y] move the picture's centre by fractions of the screen width and height. In
 * Fill mode the picture always covers the screen; in Fit mode it may be smaller, and the screen
 * around it shows [fill]. [auto] is the picture's own edge colour, offered as the first fill;
 * [custom] holds the fill colours the user added.
 */
internal data class BackgroundFrame(
    val fit: Boolean = false,
    val zoom: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val fill: Int = DEFAULT_FILL,
    val auto: Int = DEFAULT_FILL,
    val custom: List<Int> = emptyList(),
) {
    /** Keeps the framing valid for a picture of [image] pixels in a [width] x [height] box. */
    fun clamped(image: IntSize, width: Float, height: Float): BackgroundFrame {
        val hasImage = image.width > 0 && image.height > 0
        val hasBox = width > 0f && height > 0f
        if (!hasImage || !hasBox) return this
        val cover = maxOf(width / image.width, height / image.height)
        val contain = minOf(width / image.width, height / image.height)
        val zoom = zoom.coerceIn(if (fit) contain / cover else 1f, MAX_ZOOM)
        val drawnWidth = image.width * cover * zoom
        val drawnHeight = image.height * cover * zoom
        // Fill never uncovers an edge; Fit keeps the picture's centre on screen. When the picture
        // exactly spans a side, rounding can make the margin a hair below zero: never negative.
        val limitX = if (fit) .5f else maxOf(0f, (drawnWidth - width) / 2 / width)
        val limitY = if (fit) .5f else maxOf(0f, (drawnHeight - height) / 2 / height)
        return copy(zoom = zoom, x = x.coerceIn(-limitX, limitX), y = y.coerceIn(-limitY, limitY))
    }

    fun json(): String =
        JSONObject()
            .put("fit", fit)
            .put("zoom", zoom.toDouble())
            .put("x", x.toDouble())
            .put("y", y.toDouble())
            .put("fill", fill)
            .put("auto", auto)
            .put("custom", JSONArray(custom))
            .toString()

    companion object {
        const val MAX_ZOOM = 6f
        const val DEFAULT_FILL = 0xFFF2F2F7.toInt()
        const val MAX_CUSTOM = 12

        /** Reads stored framing; anything malformed or out of range falls back to defaults. */
        fun read(text: String): BackgroundFrame =
            runCatching {
                    val json = JSONObject(text)
                    BackgroundFrame(
                        fit = json.optBoolean("fit"),
                        zoom = json.optDouble("zoom", 1.0).toFloat().coerceIn(.01f, MAX_ZOOM),
                        x = json.optDouble("x", 0.0).toFloat().coerceIn(-1f, 1f),
                        y = json.optDouble("y", 0.0).toFloat().coerceIn(-1f, 1f),
                        fill = json.optInt("fill", DEFAULT_FILL) or OPAQUE,
                        auto = json.optInt("auto", DEFAULT_FILL) or OPAQUE,
                        custom =
                            json
                                .optJSONArray("custom")
                                ?.let { list ->
                                    (0 until list.length()).map { list.optInt(it) or OPAQUE }
                                }
                                .orEmpty()
                                .distinct()
                                .take(MAX_CUSTOM),
                    )
                }
                .getOrDefault(BackgroundFrame())

        private const val OPAQUE = 0xFF000000.toInt()
    }
}

/** Draws [image] framed by [frame] across this draw scope. */
internal fun DrawScope.drawFramedBackground(image: ImageBitmap, frame: BackgroundFrame) {
    val shown = frame.clamped(IntSize(image.width, image.height), size.width, size.height)
    val cover = maxOf(size.width / image.width, size.height / image.height) * shown.zoom
    val width = image.width * cover
    val height = image.height * cover
    val left = size.width / 2 + shown.x * size.width - width / 2
    val top = size.height / 2 + shown.y * size.height - height / 2
    drawRect(Color(frame.fill))
    drawImage(
        image,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.High,
    )
}

internal object BackgroundFrames {
    private fun file(context: Context) = File(context.noBackupFilesDir, "background/frame.json")

    fun load(context: Context): BackgroundFrame =
        file(context).takeIf { it.isFile }?.let { BackgroundFrame.read(it.readText()) }
            ?: BackgroundFrame()

    fun save(context: Context, frame: BackgroundFrame) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "frame.json.tmp")
        temporary.writeText(frame.json())
        if (!temporary.renameTo(target)) temporary.delete()
        CustomBackground.revision.intValue++
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** The average colour of the picture's outer edge, a natural fill around a fitted picture. */
    fun edgeColor(bitmap: Bitmap): Int {
        val samples = 24
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        fun add(px: Int) {
            red += px shr 16 and 0xFF
            green += px shr 8 and 0xFF
            blue += px and 0xFF
            count++
        }
        val right = bitmap.width - 1
        val bottom = bitmap.height - 1
        for (i in 0..samples) {
            val x = right * i / samples
            val y = bottom * i / samples
            add(bitmap.getPixel(x, 0))
            add(bitmap.getPixel(x, bottom))
            add(bitmap.getPixel(0, y))
            add(bitmap.getPixel(right, y))
        }
        return 0xFF000000.toInt() or
            ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }
}
