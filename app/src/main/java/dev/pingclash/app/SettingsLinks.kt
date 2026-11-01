// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val settingGroups =
    listOf(
        listOf("network", "dns", "rules", "apps", "lan"),
        listOf("connections", "diagnosis", "terminal"),
        listOf("appearance", "control", "privacy", "about"),
    )
private val settingTitles =
    mapOf(
        "appearance" to R.string.language_appearance,
        "network" to R.string.network_connection,
        "apps" to R.string.per_app_vpn,
        "rules" to R.string.outbound_rules,
        "lan" to R.string.local_network,
        "connections" to R.string.connections,
        "diagnosis" to R.string.connection_diagnostics,
        "terminal" to R.string.terminal,
        "control" to R.string.ui_settings_background,
        "privacy" to R.string.privacy_security,
        "about" to R.string.about_licenses,
    )

internal fun settingsTitle(id: String) =
    if (id == "dns") "DNS" else tr(settingTitles[id] ?: R.string.settings)

@Composable
internal fun SettingsMenu(onOpen: (String) -> Unit) {
    settingGroups.forEach { group ->
        SettingsCard {
            group.forEachIndexed { index, id ->
                SettingLink(id, settingsTitle(id)) { onOpen(id) }
                if (index < group.lastIndex)
                    HorizontalDivider(
                        Modifier.padding(start = 33.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
            }
        }
    }
}

@Composable
private fun SettingLink(id: String, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag(if (id in listOf("diagnosis", "terminal")) "tool-$id" else "settings-$id")
            .fluidClickable(depth = .97f, onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGlyph(settingsGlyph(id), Modifier.size(21.dp), Muted)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        AppGlyph(PcIcon.ChevronRight, Modifier.size(18.dp), Muted)
    }
}
