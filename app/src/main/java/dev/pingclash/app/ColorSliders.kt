// SPDX-License-Identifier: GPL-3.0-only
// Grid hues, lightness steps and track metrics are the picker's design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val GRID_COLUMNS = 12

/** Width over height of every picker tab: the grid's own 12 by 10 cells. */
internal const val PICKER_ASPECT = 1.2f
private const val GRID_ROWS = 10
// Blue round to green, as iOS orders its grid.
private val gridHues = listOf(205f, 220f, 250f, 280f, 320f, 350f, 8f, 24f, 40f, 52f, 75f, 130f)

/** Row 0 runs white to black; the rows below run each hue from pale to deep. */
internal fun gridColor(row: Int, column: Int): Color =
    if (row == 0) {
        val v = 1f - column / (GRID_COLUMNS - 1f)
        Color(v, v, v)
    } else Color.hsl(gridHues[column], .85f, .9f - (row - 1) * .075f)

/** Calls [pick] with the touch position on press and while dragging. */
private suspend fun PointerInputScope.trackPicks(pick: (Offset) -> Unit) = awaitEachGesture {
    val down = awaitFirstDown()
    pick(down.position)
    drag(down.id) { change ->
        pick(change.position)
        change.consume()
    }
}

/** The iOS colour grid: tap or drag across it; the chosen cell is ringed. */
@Composable
internal fun ColorGrid(selected: Color, onPick: (Color) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    val chosen = hexOf(selected)
    Canvas(
        Modifier.fillMaxWidth()
            .aspectRatio(PICKER_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .testTag("color-grid")
            .pointerInput(Unit) {
                trackPicks { at ->
                    val column =
                        (at.x / size.width * GRID_COLUMNS).toInt().coerceIn(0, GRID_COLUMNS - 1)
                    val row = (at.y / size.height * GRID_ROWS).toInt().coerceIn(0, GRID_ROWS - 1)
                    pick(gridColor(row, column))
                }
            }
    ) {
        val cell = Size(size.width / GRID_COLUMNS, size.height / GRID_ROWS)
        for (row in 0 until GRID_ROWS) for (column in 0 until GRID_COLUMNS) {
            val color = gridColor(row, column)
            val origin = Offset(column * cell.width, row * cell.height)
            // A hair of overlap hides seams between cells.
            drawRect(color, origin, Size(cell.width + 1, cell.height + 1))
            if (hexOf(color) == chosen) {
                drawRect(Color.White, origin, cell, style = Stroke(3.dp.toPx()))
                drawRect(Color.Black.copy(alpha = .35f), origin, cell, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/**
 * Every hue across, from pale at the top through full colour in the middle to black at the bottom;
 * a ring marks the chosen colour.
 */
@Composable
internal fun ColorSpectrum(selected: Color, onPick: (Color) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    Canvas(
        Modifier.fillMaxWidth()
            .aspectRatio(PICKER_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .testTag("color-spectrum")
            .pointerInput(Unit) {
                trackPicks { at ->
                    val x = (at.x / size.width).coerceIn(0f, 1f)
                    val y = (at.y / size.height).coerceIn(0f, 1f)
                    pick(spectrumColor(x, y))
                }
            }
    ) {
        drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
        val half = size.height / 2
        drawRect(
            Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), 0f, half),
            size = Size(size.width, half),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0f), Color.Black),
                half,
                size.height,
            ),
            Offset(0f, half),
            Size(size.width, half),
        )
        val (hue, saturation, brightness) = hsbOf(selected).toList()
        val y = if (brightness >= .995f) saturation / 2 else 1f - brightness / 2
        val centre = Offset(hue / 360f * size.width, y * size.height)
        drawCircle(Color.White, 11.dp.toPx(), centre, style = Stroke(3.dp.toPx()))
        drawCircle(
            Color.Black.copy(alpha = .3f),
            12.5.dp.toPx(),
            centre,
            style = Stroke(1.dp.toPx()),
        )
    }
}

/** [x] is the hue; [y] runs pale (top), full colour (middle), black (bottom). */
private fun spectrumColor(x: Float, y: Float): Color {
    val hue = (x * 360f).coerceIn(0f, 359.9f)
    return if (y < .5f) Color.hsv(hue, y * 2, 1f) else Color.hsv(hue, 1f, 2 * (1f - y))
}

/** Red, green and blue on full-width tracks that show the colour each value would give. */
@Composable
internal fun RgbSliders(color: Color, onColor: (Color) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
                Triple(R.string.color_red, color.red) { v: Float -> color.copy(red = v) },
                Triple(R.string.color_green, color.green) { v: Float -> color.copy(green = v) },
                Triple(R.string.color_blue, color.blue) { v: Float -> color.copy(blue = v) },
            )
            .forEach { (label, value, with) ->
                ChannelTrack(
                    tr(label),
                    value,
                    Brush.horizontalGradient(listOf(with(0f), with(1f))),
                ) {
                    onColor(with(it))
                }
            }
    }
}

/** One channel: its name and value above a capsule track with a round white thumb. */
@Composable
private fun ChannelTrack(label: String, value: Float, track: Brush, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
            Text(
                (value * 255).roundToInt().toString(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .height(THUMB)
                .clip(CircleShape)
                .background(track)
                .pointerInput(Unit) {
                    val radius = THUMB.toPx() / 2
                    trackPicks { at ->
                        change(((at.x - radius) / (size.width - 2 * radius)).coerceIn(0f, 1f))
                    }
                }
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value * 255, 0f..255f)
                    setProgress {
                        change((it / 255f).coerceIn(0f, 1f))
                        true
                    }
                }
        ) {
            val travel = maxWidth - THUMB
            Column(
                Modifier.offset(x = travel * value)
                    .size(THUMB)
                    .padding(3.dp)
                    .shadow(2.dp, CircleShape)
                    .background(Color.White, CircleShape)
            ) {}
        }
    }
}

private val THUMB = 30.dp
