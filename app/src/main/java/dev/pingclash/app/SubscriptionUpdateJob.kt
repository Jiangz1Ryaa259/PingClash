// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/**
 * Updates subscriptions in the background at the interval the user chose, when a network is
 * available. Downloads are HTTPS only, and a failed download or check keeps the current
 * configuration; the next run (or a manual refresh) tries again.
 */
class SubscriptionUpdateJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as PingClashApplication
        if (!app.nativeReady) return false
        worker =
            thread(name = "subscription-update") {
                try {
                    updateDue(app.repository, app.repository.data.value.client.subscriptionHours)
                } finally {
                    jobFinished(params, false)
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        // Whatever did not finish is still due and runs at the next interval.
        return false
    }

    companion object {
        private const val JOB_ID = 4201
        private const val MILLIS_PER_HOUR = 60 * 60 * 1000L

        /** Keeps one periodic job while [hours] is set and a subscription can update. */
        fun schedule(context: Context, hours: Int, subscriptions: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val interval = hours * MILLIS_PER_HOUR
            when {
                hours <= 0 || !subscriptions -> scheduler.cancel(JOB_ID)
                scheduler.getPendingJob(JOB_ID)?.intervalMillis != interval ->
                    scheduler.schedule(
                        JobInfo.Builder(
                                JOB_ID,
                                ComponentName(context, SubscriptionUpdateJob::class.java),
                            )
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPeriodic(interval)
                            .setPersisted(true)
                            .build()
                    )
            }
        }

        /** Updates every subscription that is due; returns how many were tried. */
        internal fun updateDue(repository: ProfileRepository, hours: Int): Int {
            val now = java.time.Instant.now().epochSecond
            val due =
                repository.data.value.profiles.filter {
                    subscriptionDueIn(it, hours, now, early = true) == 0L
                }
            due.forEach { profile ->
                if (Thread.currentThread().isInterrupted) return due.size
                // A failure leaves the stored profile untouched.
                runCatching { repository.refreshSubscription(profile) }
                runCatching { repository.refreshProviders(profile) }
            }
            runCatching { repository.applyPendingProvidersLive() }
            return due.size
        }
    }
}

/**
 * Seconds until [profile]'s subscription should update at an interval of [hours]; MAX_VALUE when it
 * never updates on its own. [early] accepts a run that comes up to an eighth of the interval early,
 * as a periodic job may.
 */
internal fun subscriptionDueIn(
    profile: LocalProfile,
    hours: Int,
    now: Long,
    early: Boolean = false,
): Long {
    if (!profile.autoUpdates || hours <= 0) return Long.MAX_VALUE
    val interval = hours * SECONDS_PER_HOUR
    val updated = profile.subscriptionUpdated.takeIf { it <= now } ?: 0
    val slack = if (early) interval / EARLY_FRACTION else 0
    return (updated + interval - slack - now).coerceAtLeast(0)
}

private const val SECONDS_PER_HOUR = 60 * 60L
private const val EARLY_FRACTION = 8
