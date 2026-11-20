// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray

private const val MAX_CONNECTION_ROWS = 512

internal fun parseConnectionRows(value: String): List<ConnectionRow> {
    val raw = JSONArray(value)
    return (0 until raw.length().coerceAtMost(MAX_CONNECTION_ROWS))
        .map {
            val row = raw.getJSONObject(it)
            ConnectionRow(
                row.getLong("id"),
                row.getString("network"),
                row.getString("route"),
                row.getString("destination"),
                row.getLong("seconds"),
                row.getLong("uploaded"),
                row.getLong("downloaded"),
                row.optString("ruleType"),
                row.optString("rulePayload"),
            )
        }
        .sortedByDescending { it.id }
}

@Composable
internal fun ConnectionListItem(row: ConnectionRow, reason: String? = null, onClose: () -> Unit) {
    ConnectionSwipeRow(row.id, onClose) {
        Surface(shape = UiCorners.Card, color = Panel, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        listOf(
                                row.network.uppercase(),
                                row.ruleType.takeIf { reason == null }.orEmpty(),
                                row.route,
                            )
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Why it went there, in terms of the user's own choices.
                    if (reason != null)
                        Text(
                            reason,
                            Modifier.testTag("connection-reason"),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Text(
                        row.destination.ifEmpty { tr(R.string.destination_not_recorded) },
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "↑ ${formatBytes(row.up)}  ↓ ${formatBytes(row.down)} · ${durationText(row.seconds)}",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
