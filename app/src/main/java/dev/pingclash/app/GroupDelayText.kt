// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

internal fun groupDelayText(value: ProxyDelay): String =
    if (value.total == 0) tr(R.string.delay_group_empty)
    else
        trPlural(
            R.plurals.delay_group_progress,
            value.total,
            value.completed,
            value.total,
            value.passed,
        )
