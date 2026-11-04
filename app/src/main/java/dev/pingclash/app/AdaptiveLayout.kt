// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Grouped rows and cards read best at about a phone-and-a-half wide. */
internal val READABLE_WIDTH = 720.dp

/**
 * Full width on a phone; on a tablet at most [max], centred. Placed inside a scroll container, the
 * whole screen still scrolls.
 */
internal fun Modifier.readableWidth(max: Dp = READABLE_WIDTH): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** Side padding that centres at most [max] of content in [width], and never less than [min]. */
internal fun readableSidePadding(width: Dp, min: Dp = 20.dp, max: Dp = READABLE_WIDTH): Dp =
    maxOf(min, (width - max) / 2)
