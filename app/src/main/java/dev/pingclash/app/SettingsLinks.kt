// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val settingGroups =
    listOf(
        listOf("network", "dns", "rules", "routing", "apps"),
        listOf("connections", "diagnosis", "terminal"),
        listOf("appearance", "control", "automation", "privacy", "setup", "about"),
    )
private val settingTitles =
    mapOf(
        "appearance" to R.string.language_appearance,
        "network" to R.string.network_connection,
        "apps" to R.string.per_app_vpn,
        "rules" to R.string.outbound_rules,
        "routing" to R.string.service_routing,
        "setup" to R.string.setup_guide,
        "connections" to R.string.connections,
        "diagnosis" to R.string.connection_diagnostics,
        "terminal" to R.string.terminal,
        "control" to R.string.ui_settings_background,
        "automation" to R.string.ui_settings_automation,
        "privacy" to R.string.privacy_backup,
        "about" to R.string.about_licenses,
    )

internal fun settingsTitle(id: String) =
    if (id == "dns") "DNS" else tr(settingTitles[id] ?: R.string.settings)

/** The colour of a settings entry's icon tile, shared by pages that echo the entry. */
internal fun settingTint(id: String) = Color(settingTints.getValue(id))

/** The tinted square behind each entry's glyph, as in iOS Settings. */
private val settingTints =
    mapOf(
        "network" to 0xFF0A84FF,
        "dns" to 0xFF5E5CE6,
        "rules" to 0xFFFF9500,
        "routing" to 0xFF00C7BE,
        "setup" to 0xFFFFCC00,
        "apps" to 0xFF34C759,
        "connections" to 0xFFFF2D55,
        "diagnosis" to 0xFFFF3B30,
        "terminal" to 0xFF3A3A3C,
        "appearance" to 0xFFAF52DE,
        "control" to 0xFFFF9F0A,
        "automation" to 0xFF5856D6,
        "privacy" to 0xFF636366,
        "about" to 0xFF8E8E93,
    )

private val groupTitles =
    listOf(R.string.ui_group_proxy, R.string.ui_group_tools, R.string.ui_group_general)

/** The settings home: three groups of entries, each showing its current value. */
@Composable
internal fun SettingsMenu(client: ClientSettings, onOpen: (String) -> Unit) {
    val version = rememberVersionName()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        settingGroups.forEachIndexed { group, ids ->
            SettingsSection(tr(groupTitles[group])) {
                ids.forEachIndexed { index, id ->
                    if (index > 0) RowDivider()
                    NavRow(
                        settingsTitle(id),
                        if (id in listOf("diagnosis", "terminal")) "tool-$id" else "settings-$id",
                        value = if (id == "about") version else settingValue(id, client),
                        icon = settingsGlyph(id) to settingTint(id),
                    ) {
                        onOpen(id)
                    }
                }
            }
        }
    }
}

// What each entry shows on the right: the label of its current choice.
private val valueLabels =
    mapOf(
        "rules" to
            mapOf(
                "rule" to R.string.rules,
                "global" to R.string.global_proxy,
                "direct" to R.string.direct,
            ),
        "apps" to
            mapOf(
                "all" to R.string.all_apps,
                "exclude" to R.string.exclude_selected_apps,
                "include" to R.string.only_selected_apps,
            ),
        "appearance" to
            mapOf(
                "ocean" to R.string.ui_scene_ocean,
                "clouds" to R.string.ui_scene_clouds,
                "none" to R.string.ui_scene_none,
                "image" to R.string.ui_scene_image,
            ),
    )

private fun settingValue(id: String, client: ClientSettings): String? =
    when (id) {
        "dns" -> if (client.dnsMode == "https") "DoH" else "TCP"
        "routing" ->
            client.serviceRoutes.size.takeIf { it > 0 }?.let { tr(R.string.routing_summary, it) }
                ?: tr(R.string.routing_default)
        else -> valueLabels[id]?.get(choiceOf(id, client))?.let { tr(it) }
    }

private fun choiceOf(id: String, client: ClientSettings) =
    when (id) {
        "rules" -> client.mode
        "apps" -> client.appMode
        "appearance" -> client.scene
        else -> ""
    }

@Composable
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    }
}

/**
 * The top of every settings page: on a detail page a blue "< Settings" back button, then the large
 * title; Save appears on the right while typed changes are pending.
 */
@Composable
internal fun SettingsHeading(
    page: String,
    dirty: Boolean,
    onBack: () -> Unit,
    /** Saves the typed changes; null while they cannot be saved. */
    onSave: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        if (page.isNotEmpty() || dirty)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page.isNotEmpty()) {
                    val label = tr(R.string.back)
                    Row(
                        Modifier.fluidClickable(depth = .95f, onClick = onBack)
                            .testTag("settings-back")
                            .semantics(mergeDescendants = true) { contentDescription = label }
                            .padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppGlyph(BACK_GLYPH, Modifier.size(22.dp), pageInk().accent)
                        Text(
                            tr(R.string.settings),
                            color = pageInk().accent,
                            style = MaterialTheme.typography.bodyLarge.onPage(),
                        )
                    }
                }
                Box(Modifier.weight(1f))
                if (dirty)
                    TextButton(
                        { onSave?.invoke() },
                        enabled = onSave != null,
                        modifier = Modifier.testTag("settings-save"),
                    ) {
                        Text(
                            tr(R.string.save_settings),
                            color = pageInk().accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                trailing?.invoke()
            }
        PageHeading(
            settingsTitle(page),
            tag = if (page.isEmpty()) "page-title-settings" else "settings-detail-title",
        )
    }
}
