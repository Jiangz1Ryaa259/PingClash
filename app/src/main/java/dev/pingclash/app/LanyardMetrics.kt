// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class LanyardMetrics(val height: Dp, val card: Dp, val top: Dp)

internal fun lanyardMetrics(large: Boolean, stage: Dp?): LanyardMetrics {
    val preferred = if (large) 350.dp else 292.dp
    val height = stage ?: preferred + 84.dp
    val card = (height - if (height < 300.dp) 42.dp else 84.dp).coerceIn(90.dp, preferred)
    return LanyardMetrics(height, card, (height - card - 18.dp).coerceAtLeast(8.dp))
}
