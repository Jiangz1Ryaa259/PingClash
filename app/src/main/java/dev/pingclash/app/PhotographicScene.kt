// SPDX-License-Identifier: GPL-3.0-only
// Mesh resolution and small optical offsets are deliberate rendering values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Bitmap
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

internal data class SceneFrame(
    val width: Float,
    val height: Float,
    val phase: Float,
    val depth: Float,
)

/** Retained bitmap/mesh: no bitmap allocation, screen capture or network access per frame. */
internal class PhotographicScene(private val bitmap: Bitmap, private val clouds: Boolean = false) {
    private val columns = 16
    private val rows = 28
    private val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: android.graphics.Canvas, frame: SceneFrame, opacity: Float = 1f) {
        if (opacity <= 0f) return
        val theta = frame.phase * 2 * PI
        var index = 0
        for (y in 0..rows) for (x in 0..columns) {
            val px = x.toFloat() / columns
            val py = y.toFloat() / rows
            val amplitude = if (clouds) .0015f else .0035f
            vertices[index++] =
                bitmap.width *
                    (px + sin(py * 7 + theta * 2).toFloat() * amplitude * sin(px * PI).toFloat())
            vertices[index++] =
                bitmap.height * (py + sin(px * 12 + py * 7 - theta * 3).toFloat() * amplitude)
        }
        val scale =
            max(frame.width / bitmap.width, frame.height / bitmap.height) *
                (1.08f + frame.depth * .045f)
        val x =
            (frame.width - bitmap.width * scale) / 2 + sin(theta).toFloat() * frame.width * .004f
        // The photo is portrait. A wide screen shows a horizontal band of it: keep the band near
        // the top so the sky stays behind the page titles instead of dark water.
        val anchor = if (frame.width > frame.height) WIDE_ANCHOR else .5f
        val y = (frame.height - bitmap.height * scale) * anchor + frame.depth * frame.height * .035f
        val checkpoint = canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawBitmapMesh(bitmap, columns, rows, vertices, 0, null, 0, paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}

/** Where a wide frame's band sits in the photo, from the top (0) to the centre (.5). */
private const val WIDE_ANCHOR = .06f
