// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The dashboard's speed panel (HomeDashboard.kt).

@Composable
internal fun SpeedPanel(vpn: VpnUiState) {
    val download = Mint
    val upload = Muted // cool grey-blue beside the mint download; no warm accents
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), TileShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SpeedFigure(tr(R.string.download), "↓ ${formatBytes(vpn.downloadSpeed)}/s", download)
            SpeedFigure(tr(R.string.upload), "↑ ${formatBytes(vpn.uploadSpeed)}/s", upload)
        }
        SpeedChart(vpn.history, download, upload)
    }
}

@Composable
private fun SpeedFigure(label: String, value: String, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 6.dp).size(8.dp).background(color, CircleShape))
            Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            value,
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold,
                ),
            maxLines = 1,
        )
    }
}

// A steady scale: the peak rounded up to a power of two, at least 16 KiB/s.
internal fun niceScale(peak: Long): Long {
    var step = 16L * 1024
    while (step < peak) {
        if (step > Long.MAX_VALUE / 2) return Long.MAX_VALUE
        step *= 2
    }
    return step
}

/**
 * The last minute of samples, faithfully: a monotone curve (smooth, but it cannot invent peaks),
 * download as a filled area, upload as a line, on a steady scale with its top labelled. Idle time
 * is a flat line at the bottom, not an empty box.
 */
@Composable
private fun SpeedChart(samples: List<SpeedSample>, download: Color, upload: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val scale = remember(samples) { niceScale(samples.maxOfOrNull { maxOf(it.up, it.down) } ?: 0L) }
    val description = tr(R.string.traffic_over_the_last_60_seconds)
    Box {
        Box(
            Modifier.fillMaxWidth()
                .height(110.dp)
                .testTag("traffic-chart")
                .semantics { contentDescription = description }
                .drawWithCache {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    val gridStroke = 1.dp.toPx()
                    val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val paths = speedPaths(samples, scale)
                    val fill = download.copy(alpha = .18f)
                    onDrawBehind {
                        drawLine(
                            grid,
                            Offset.Zero,
                            Offset(size.width, 0f),
                            gridStroke,
                            pathEffect = dash,
                        )
                        drawLine(
                            grid,
                            Offset(0f, size.height / 2),
                            Offset(size.width, size.height / 2),
                            gridStroke,
                            pathEffect = dash,
                        )
                        drawLine(
                            grid,
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                            gridStroke,
                        )
                        drawPath(paths.area, fill)
                        drawPath(paths.down, download, style = line)
                        drawPath(paths.up, upload, style = line)
                    }
                }
        )

        Text(
            "${formatBytes(scale)}/s",
            Modifier.align(Alignment.TopEnd).padding(top = 2.dp),
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Monotone cubic through the samples (Fritsch-Carlson): smooth, yet it never overshoots a sample or
 * dips below zero, so the curve still shows only what was measured.
 */
internal fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.size < 2) return path.apply { points.firstOrNull()?.let { moveTo(it.x, it.y) } }
    path.moveTo(points[0].x, points[0].y)
    val n = points.size
    val dx = FloatArray(n - 1) { (points[it + 1].x - points[it].x).coerceAtLeast(1e-4f) }
    val slope = FloatArray(n - 1) { (points[it + 1].y - points[it].y) / dx[it] }
    val tangent = FloatArray(n)
    tangent[0] = slope[0]
    tangent[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) tangent[i] =
        if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) {
            tangent[i] = 0f
            tangent[i + 1] = 0f
        } else {
            val a = tangent[i] / slope[i]
            val b = tangent[i + 1] / slope[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / kotlin.math.sqrt(h)
                tangent[i] = t * a * slope[i]
                tangent[i + 1] = t * b * slope[i]
            }
        }
    }
    for (i in 0 until n - 1) {
        val from = points[i]
        val to = points[i + 1]
        val third = dx[i] / 3f
        path.cubicTo(
            from.x + third,
            from.y + tangent[i] * third,
            to.x - third,
            to.y - tangent[i + 1] * third,
            to.x,
            to.y,
        )
    }
    return path
}

private data class SpeedPaths(val down: Path, val up: Path, val area: Path)

private fun CacheDrawScope.speedPaths(samples: List<SpeedSample>, scale: Long): SpeedPaths {
    fun point(index: Int, value: Long) =
        Offset(
            size.width * (60 - samples.size + index) / 59f,
            size.height * (1f - (value.toFloat() / scale).coerceIn(0f, 1f)),
        )
    val valid = size.width > 0f && size.height > 0f && samples.size >= 2
    val down =
        if (valid) smoothPath(samples.mapIndexed { i, sample -> point(i, sample.down) }) else Path()
    val up =
        if (valid) smoothPath(samples.mapIndexed { i, sample -> point(i, sample.up) }) else Path()
    val area =
        Path().apply {
            if (valid) {
                addPath(down)
                lineTo(point(samples.lastIndex, 0).x, size.height)
                lineTo(point(0, 0).x, size.height)
                close()
            }
        }
    return SpeedPaths(down, up, area)
}
