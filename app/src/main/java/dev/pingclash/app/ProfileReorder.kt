// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Drag-to-reorder for the profile list. The lifted card follows the finger while the others slide
 * aside (their placement animates); the order is committed once, on drop.
 */
internal class ProfileReorder(
    private val list: LazyListState,
    private val haptics: HapticFeedback,
    private val edge: Float,
) {
    var dragged by mutableStateOf<String?>(null)
        private set

    private var order by mutableStateOf<List<String>>(emptyList())
    // Held after a drop until storage reports the same order, so the list never snaps back.
    private var held by mutableStateOf<List<String>?>(null)
    private var start: List<String> = emptyList()
    private var initial = 0
    private var delta by mutableFloatStateOf(0f)
    // The stored list from the latest composition; gestures read it when they start.
    private var latest: List<LocalProfile> = emptyList()

    /** The order to show: live while dragging or just dropped, otherwise as stored. */
    fun display(profiles: List<LocalProfile>): List<LocalProfile> {
        latest = profiles
        val ids = if (dragged != null) order else held
        if (ids == null || ids == profiles.map { it.id }) {
            if (dragged == null) held = null
            return profiles
        }
        val byId = profiles.associateBy { it.id }
        val shown = ids.mapNotNull(byId::get)
        // A profile added or removed meanwhile: fall back to the stored list.
        return if (shown.size == profiles.size) shown else profiles
    }

    /** Vertical offset that keeps the lifted card under the finger. */
    fun translation(id: String): Float {
        val item = visible(id)?.takeIf { id == dragged } ?: return 0f
        return initial + delta - item.offset
    }

    fun begin(id: String) {
        val item = visible(id) ?: return
        order = display(latest).map { it.id }
        start = order
        initial = item.offset
        delta = 0f
        dragged = id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun drag(dy: Float) {
        val id = dragged
        val item = id?.let(::visible)
        if (id != null && item != null) {
            delta += dy
            val top = initial + delta
            autoScroll(dy, top, top + item.size)
            val center = (top + item.size / 2f).toInt()
            list.layoutInfo.visibleItemsInfo
                .firstOrNull {
                    it.key != id && it.key in order && center in it.offset until it.offset + it.size
                }
                ?.let { target ->
                    order =
                        order.toMutableList().apply {
                            add(indexOf(target.key), removeAt(indexOf(id)))
                        }
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
        }
    }

    // Scroll when the card is pushed against the top or bottom of the list.
    private fun autoScroll(dy: Float, top: Float, bottom: Float) {
        val info = list.layoutInfo
        val upward = dy < 0 && top < info.viewportStartOffset + edge
        val downward = dy > 0 && bottom > info.viewportEndOffset - edge
        if (upward || downward) list.dispatchRawDelta(dy)
    }

    /** Ends the drag; returns the new order when it changed. */
    fun end(): List<String>? {
        if (dragged == null) return null
        dragged = null
        delta = 0f
        held = order
        return order.takeIf { it != start }
    }

    /** One step up or down, for accessibility actions. */
    fun step(id: String, offset: Int): List<String>? {
        val ids = display(latest).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + offset
        if (from < 0 || to !in ids.indices) return null
        ids.add(to, ids.removeAt(from))
        held = ids
        return ids
    }

    private fun visible(id: String): LazyListItemInfo? =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
}

/** Long-press anywhere on the card, or drag the grip immediately. */
internal fun Modifier.reorderable(
    reorder: ProfileReorder,
    id: String,
    commit: (List<String>) -> Unit,
    immediate: Boolean = false,
): Modifier =
    pointerInput(id, immediate) {
        val start = { _: androidx.compose.ui.geometry.Offset -> reorder.begin(id) }
        val finish = { reorder.end()?.let(commit) ?: Unit }
        val move =
            {
                change: androidx.compose.ui.input.pointer.PointerInputChange,
                amount: androidx.compose.ui.geometry.Offset ->
                change.consume()
                reorder.drag(amount.y)
            }
        if (immediate) detectDragGestures(start, { finish() }, { finish() }, move)
        else detectDragGesturesAfterLongPress(start, { finish() }, { finish() }, move)
    }
