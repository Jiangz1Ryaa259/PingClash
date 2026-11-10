// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** Ad blocking choices: off, ads, or ads and tracking. */
internal fun blockingOptions() =
    listOf(
        BLOCKING_OFF to tr(R.string.blocking_off),
        BLOCKING_ADS to tr(R.string.blocking_ads),
        BLOCKING_TELEMETRY to tr(R.string.blocking_telemetry),
    )
