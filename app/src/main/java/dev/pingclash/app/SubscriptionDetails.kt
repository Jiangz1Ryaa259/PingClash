// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun localDateTime(seconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(UiStrings.resources().configuration.locales[0])
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(seconds))

@Composable
internal fun SubscriptionDetails(profile: LocalProfile) {
    if (profile.source == null && profile.quota == null && profile.remote == null) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        profile.remote?.let { remote ->
            val specs =
                androidx.compose.runtime.remember(remote.original) {
                    runCatching { RemoteProfiles.plan(remote.original) }.getOrNull()
                }
            specs?.let {
                for (i in 0 until it.length()) {
                    val spec = it.getJSONObject(i)
                    Text(
                        tr(
                            R.string.provider_status,
                            spec.getInt("index"),
                            spec.getString("kind"),
                            spec.getInt("interval"),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    remote.updated[spec.getString("id")]?.let { stamp ->
                        Text(
                            tr(R.string.provider_updated, localDateTime(stamp)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        profile.quota?.let { quota ->
            quota.upload?.let {
                Text(
                    tr(R.string.profile_upload, formatBytes(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            quota.download?.let {
                Text(
                    tr(R.string.profile_download, formatBytes(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                tr(
                    R.string.subscription_used,
                    quota.used?.let(::formatBytes) ?: tr(R.string.subscription_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tr(
                    R.string.subscription_total,
                    quota.total?.takeIf { it > 0 }?.let(::formatBytes)
                        ?: tr(R.string.subscription_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            quota.remaining?.let {
                Text(
                    tr(R.string.subscription_remaining, formatBytes(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                tr(
                    R.string.subscription_expires,
                    quota.expire?.let(::localDateTime) ?: tr(R.string.subscription_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
