// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONArray
import org.json.JSONObject

/** How much of the quota was used at one subscription update. */
data class UsageSample(val at: Long, val used: Long) {
    fun json(): JSONObject = JSONObject().put("at", at).put("used", used)

    companion object {
        fun read(value: JSONObject?): UsageSample? =
            value
                ?.takeIf { it.has("at") && it.has("used") }
                ?.let { UsageSample(it.optLong("at"), it.optLong("used")) }
                ?.takeIf { it.at > 0 && it.used >= 0 }

        fun readAll(values: JSONArray?): List<UsageSample> =
            if (values == null) emptyList()
            else
                (0 until minOf(values.length(), USAGE_SAMPLES))
                    .mapNotNull { read(values.optJSONObject(it)) }
                    .sortedBy { it.at }
    }
}

/** Samples kept per subscription: enough for a pace, small enough to say nothing more. */
internal const val USAGE_SAMPLES = 12

/** Older samples describe a past billing period, not the current pace. */
internal const val USAGE_WINDOW_SECONDS = 31L * 24 * 60 * 60

/** A pace from samples closer together than this would swing with one evening's use. */
internal const val MIN_PACE_SECONDS = 12L * 60 * 60

/** The samples after one more update: a drop in usage starts a new period. */
internal fun List<UsageSample>.plusUsage(quota: SubscriptionQuota?, now: Long): List<UsageSample> {
    val used = quota?.used ?: return this
    val recent = filter { now - it.at in 0..USAGE_WINDOW_SECONDS }
    val period = if (recent.lastOrNull()?.let { used < it.used } == true) emptyList() else recent
    return (period + UsageSample(now, used)).takeLast(USAGE_SAMPLES)
}

internal sealed interface QuotaForecast {
    /** At the current pace the data runs out at this time (epoch seconds). */
    data class RunsOut(val at: Long) : QuotaForecast

    /** At the current pace the data lasts past the subscription's expiry. */
    data object LastsUntilExpiry : QuotaForecast
}

/**
 * When the data runs out at the pace between the oldest and newest sample of this period. Null
 * without a total, with less than [MIN_PACE_SECONDS] between samples, or when nothing was used.
 */
internal fun quotaForecast(quota: SubscriptionQuota?, samples: List<UsageSample>): QuotaForecast? {
    val remaining = quota?.remaining?.takeIf { it > 0 }
    val first = samples.firstOrNull()
    val last = samples.lastOrNull()
    // Bytes per second over the period so far.
    val pace =
        if (first == null || last == null || last.at - first.at < MIN_PACE_SECONDS) null
        else ((last.used - first.used).toDouble() / (last.at - first.at)).takeIf { it > 0 }
    if (remaining == null || last == null || pace == null) return null
    val ahead = (remaining / pace).toLong()
    val runsOut = last.at + ahead.coerceIn(0, Long.MAX_VALUE - last.at)
    val expire = quota?.expire?.takeIf { it > 0 }
    return if (expire != null && runsOut >= expire) QuotaForecast.LastsUntilExpiry
    else QuotaForecast.RunsOut(runsOut)
}
