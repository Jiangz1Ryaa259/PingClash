// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Remind this long before the data runs out or the subscription expires. */
internal const val REMINDER_AHEAD_SECONDS = 3L * 24 * 60 * 60

/** Something worth one reminder: [key] stays the same until the period or the date changes. */
internal data class QuotaEvent(
    val profileId: String,
    val key: String,
    val expiry: Boolean,
    val at: Long,
)

/**
 * The reminders due for these profiles now. Checked whenever a subscription's quota changes (an
 * update, in the app or in the background), so a reminder can be at most one update late.
 */
internal fun dueQuotaEvents(profiles: List<LocalProfile>, now: Long): List<QuotaEvent> =
    profiles.flatMap { profile ->
        val quota = profile.quota ?: return@flatMap emptyList()
        val expire = quota.expire?.takeIf { it - now in 0..REMINDER_AHEAD_SECONDS }
        val runsOut =
            (quotaForecast(quota, profile.usage) as? QuotaForecast.RunsOut)?.at?.takeIf {
                it - now <= REMINDER_AHEAD_SECONDS
            }
        listOfNotNull(
            expire?.let { QuotaEvent(profile.id, "expire:$it", true, it) },
            // One reminder per period: the period starts at its first sample.
            runsOut?.let {
                QuotaEvent(profile.id, "quota:${profile.usage.first().at}", false, maxOf(it, now))
            },
        )
    }

/** Posts each due reminder once, only while the user has reminders on. */
internal object QuotaReminder {
    private const val CHANNEL = "quota"
    private const val PREFS = "quota-reminders"
    private const val FIRST_ID = 100
    /** Two notification ids per profile, kept clear of the app's other notifications. */
    private const val ID_MASK = 0xffff

    fun start(app: PingClashApplication) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            app.repository.data
                .map { data -> if (data.client.quotaReminder) data.profiles else emptyList() }
                .distinctUntilChanged()
                .collect { profiles -> runCatching { remind(app, profiles) } }
        }
    }

    private fun remind(context: Context, profiles: List<LocalProfile>) {
        if (profiles.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = Instant.now().epochSecond
        val due = dueQuotaEvents(profiles, now)
        val sent = prefs.getStringSet("sent", emptySet()).orEmpty()
        val fresh =
            due.filter { "${it.profileId}|${it.key}" !in sent }
                .filter { event ->
                    post(context, profiles.first { it.id == event.profileId }, event)
                }
        // Kept until the profile is removed, so a pace that dips and returns reminds only once.
        val ids = profiles.map { it.id }.toSet()
        val kept = sent.filter { it.substringBefore('|') in ids }
        prefs
            .edit()
            .putStringSet("sent", (kept + fresh.map { "${it.profileId}|${it.key}" }).toSet())
            .apply()
    }

    /** False when notifications are off, so the reminder comes once they are allowed. */
    private fun post(context: Context, profile: LocalProfile, event: QuotaEvent): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null || !manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                tr(R.string.quota_reminder_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val date =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(event.at))
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return runCatching {
                manager.notify(
                    FIRST_ID + (profile.id.hashCode() and ID_MASK) * 2 + if (event.expiry) 1 else 0,
                    Notification.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_status)
                        .setContentTitle(profile.name)
                        .setContentText(
                            tr(
                                if (event.expiry) R.string.quota_reminder_expires
                                else R.string.quota_reminder_runs_out,
                                date,
                            )
                        )
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build(),
                )
            }
            .isSuccess
    }
}
