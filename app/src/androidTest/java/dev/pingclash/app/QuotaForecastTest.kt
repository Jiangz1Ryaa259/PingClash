// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The forecast comes from the pace between updates in the current period, and only from that. */
@RunWith(AndroidJUnit4::class)
class QuotaForecastTest {
    private val day = 24 * 60 * 60L
    private val gib = 1L shl 30

    private fun quota(used: Long, total: Long = 100 * gib, expire: Long? = null) =
        SubscriptionQuota(upload = 0, download = used, total = total, expire = expire)

    @Test
    fun samplesFollowTheCurrentPeriodOnly() {
        val start = 1_800_000_000L
        var samples = emptyList<UsageSample>()
        samples = samples.plusUsage(quota(10 * gib), start)
        samples = samples.plusUsage(quota(20 * gib), start + day)
        assertEquals(2, samples.size)
        // A quota without usage leaves the samples as they are.
        assertEquals(samples, samples.plusUsage(SubscriptionQuota(total = 100 * gib), start))
        // Usage went down: the quota was reset, so the old pace no longer applies.
        samples = samples.plusUsage(quota(1 * gib), start + 2 * day)
        assertEquals(listOf(UsageSample(start + 2 * day, 1 * gib)), samples)
        // Samples older than the window are dropped, and only the last twelve are kept.
        samples = samples.plusUsage(quota(2 * gib), start + 40 * day)
        assertEquals(1, samples.size)
        repeat(20) { samples = samples.plusUsage(quota((3 + it) * gib), start + 41 * day + it) }
        assertEquals(USAGE_SAMPLES, samples.size)
        assertEquals(samples, UsageSample.readAll(JSONArray(samples.map { it.json() })))
    }

    @Test
    fun theForecastUsesThePaceBetweenTheFirstAndLastSample() {
        val start = 1_800_000_000L
        val samples = listOf(UsageSample(start, 10 * gib), UsageSample(start + 2 * day, 30 * gib))
        // 10 GiB a day with 70 GiB left: seven more days.
        assertEquals(
            QuotaForecast.RunsOut(start + 9 * day),
            quotaForecast(quota(30 * gib), samples),
        )
        assertEquals(
            QuotaForecast.LastsUntilExpiry,
            quotaForecast(quota(30 * gib, expire = start + 5 * day), samples),
        )
        assertEquals(
            QuotaForecast.RunsOut(start + 9 * day),
            quotaForecast(quota(30 * gib, expire = start + 20 * day), samples),
        )
    }

    @Test
    fun remindersAreDueThreeDaysAheadAndOncePerPeriod() {
        val now = 1_800_000_000L
        fun profile(id: String, quota: SubscriptionQuota, usage: List<UsageSample> = emptyList()) =
            LocalProfile(id, id, null, emptyList(), 0, false, quota = quota, usage = usage)
        val fast = listOf(UsageSample(now - 2 * day, 10 * gib), UsageSample(now, 90 * gib))
        val events =
            dueQuotaEvents(
                listOf(
                    // 40 GiB a day with 10 GiB left: out in six hours.
                    profile("out", quota(90 * gib), fast),
                    profile("expires", quota(10 * gib, expire = now + 2 * day)),
                    profile("later", quota(10 * gib, expire = now + 10 * day)),
                    profile("past", quota(10 * gib, expire = now - day)),
                ),
                now,
            )
        assertEquals(
            listOf(
                QuotaEvent("out", "quota:${now - 2 * day}", false, now + 6 * 60 * 60),
                QuotaEvent("expires", "expire:${now + 2 * day}", true, now + 2 * day),
            ),
            events,
        )
    }

    @Test
    fun noForecastWithoutAPace() {
        val start = 1_800_000_000L
        val apart = listOf(UsageSample(start, 10 * gib), UsageSample(start + day, 20 * gib))
        // Too close together to say anything.
        assertNull(
            quotaForecast(
                quota(20 * gib),
                listOf(UsageSample(start, 10 * gib), UsageSample(start + 3600, 20 * gib)),
            )
        )
        // Nothing used, no total, or nothing left.
        assertNull(
            quotaForecast(
                quota(10 * gib),
                listOf(UsageSample(start, 10 * gib), UsageSample(start + day, 10 * gib)),
            )
        )
        assertNull(quotaForecast(quota(20 * gib, total = 0), apart))
        assertNull(quotaForecast(quota(100 * gib), apart))
        assertNull(quotaForecast(quota(20 * gib), apart.take(1)))
    }
}
