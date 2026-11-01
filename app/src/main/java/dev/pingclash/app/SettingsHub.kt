// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pingclash.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsHub(
    data: AppData,
    vpn: VpnUiState,
    locked: Boolean,
    model: AppViewModel,
    onDiagnose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf("") }
    val saver =
        androidx.compose.runtime.saveable.Saver<ClientSettings, String>(
            save = { it.json().toString() },
            restore = { ClientSettings.read(org.json.JSONObject(it)) },
        )
    var draft by rememberSaveable(stateSaver = saver) { mutableStateOf(data.client) }
    var baseline by remember { mutableStateOf(data.client) }
    var dns by rememberSaveable(data.dnsAddress) { mutableStateOf(data.dnsAddress) }
    var target by rememberSaveable(data.probeUrl) { mutableStateOf(data.probeUrl) }
    LaunchedEffect(data.client) {
        draft = mergeSettingsDelta(baseline, draft, data.client)
        baseline = data.client
    }
    val uiBusy = model.ui.collectAsState().value.busy
    val appearanceEditable = !uiBusy
    fun changeDraft(value: ClientSettings) {
        val previous = draft
        draft = value
        val immediate = mergeSettingsDelta(previous, value, data.client, deferredSettingFields)
        runCatching { validateClientSettings(immediate) }
            .onSuccess { model.patchSettings(previous, value) }
            .onFailure {
                // Put the control back and say why, instead of silently not saving.
                draft = previous
                model.message(it.message ?: tr(R.string.invalid_app_routing_list_include_mode_requ))
            }
    }
    var showProviderRules by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val textDirty =
        deferredSettingFields.any {
            data.client.json().opt(it)?.toString() != draft.json().opt(it)?.toString()
        } || dns != data.dnsAddress || target != data.probeUrl
    var confirmBack by remember { mutableStateOf(false) }
    fun back() {
        if (textDirty) confirmBack = true else page = ""
    }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(page.isNotEmpty() && !confirmBack) { events ->
        try {
            events.collect { backProgress = if (textDirty) 0f else it.progress }
            backProgress = 0f
            back()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }
    val pages = rememberSaveableStateHolder()
    ContextReminder(
        "forwarding-locked:$page:${vpn.sessionStartedElapsed}",
        tr(R.string.disconnect_the_vpn_before_changing_forward),
        vpn.active && locked && page in setOf("dns", "network", "apps", "rules", "lan"),
    )
    SettingsSlide(page, backProgress) { shown ->
        pages.SaveableStateProvider(shown) {
            val listPage = shown in setOf("apps", "connections")
            val scroll = rememberScrollState()
            val heading: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shown.isNotEmpty())
                        IconButton(
                            { back() },
                            Modifier.testTag("settings-back").semantics {
                                contentDescription = tr(R.string.back)
                            },
                        ) {
                            AppGlyph(BACK_GLYPH)
                        }
                    PageHeading(
                        settingsTitle(shown),
                        Modifier.weight(1f),
                        if (shown.isEmpty()) "page-title-settings" else "settings-detail-title",
                    )
                    if (textDirty)
                        TextButton(
                            { model.clientSettings(draft, dns.trim(), target.trim()) },
                            enabled = !locked,
                            modifier = Modifier.testTag("settings-save"),
                        ) {
                            Text(tr(R.string.save_settings))
                        }
                }
            }
            Column(
                Modifier.fillMaxSize()
                    .imePadding()
                    .then(if (listPage) Modifier else Modifier.verticalScroll(scroll))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = LocalNavigationClearance.current)
                    .testTag("settings-page"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!listPage) heading()
                when (shown) {
                    "" ->
                        SettingsMenu { id ->
                            when (id) {
                                "diagnosis" -> onDiagnose()
                                "terminal" ->
                                    context.startActivity(
                                        Intent(context, TerminalActivity::class.java)
                                    )
                                else -> page = id
                            }
                        }
                    "connections" -> ConnectionsPanel(vpn, model, heading)
                    "dns" ->
                        SettingsCard {
                            SettingSwitch(
                                tr(R.string.use_subscription_dns),
                                draft.useSubscriptionDns,
                                !locked,
                            ) {
                                changeDraft(draft.copy(useSubscriptionDns = it))
                            }
                            Choices(
                                tr(R.string.mode),
                                draft.dnsMode,
                                listOf("tcp" to "DNS over TCP", "https" to "DNS over HTTPS"),
                                !locked,
                            ) {
                                changeDraft(draft.copy(dnsMode = it))
                            }
                            SettingField("VPN DNS", dns, !locked) { if (it.length <= 80) dns = it }
                            if (draft.dnsMode == "https")
                                SettingField(
                                    tr(R.string.encrypted_dns_url),
                                    draft.dnsHttps,
                                    !locked,
                                ) {
                                    if (it.length <= 2048) draft = draft.copy(dnsHttps = it)
                                }
                        }
                    "network" -> {
                        SettingsCard {
                            SettingSwitch(
                                tr(R.string.auto_reconnect),
                                draft.autoReconnect,
                                !locked,
                            ) {
                                changeDraft(draft.copy(autoReconnect = it))
                            }

                            SettingSwitch(tr(R.string.enable_ipv6), draft.ipv6, !locked) {
                                changeDraft(draft.copy(ipv6 = it))
                            }

                            SettingSwitch(tr(R.string.enable_udp), draft.udp, !locked) {
                                changeDraft(draft.copy(udp = it))
                            }
                            NumberSetting("MTU", draft.mtu, 1280..9000, !locked) {
                                changeDraft(draft.copy(mtu = it))
                            }
                            NumberSetting(
                                tr(R.string.connect_timeout_seconds),
                                draft.connectTimeout,
                                3..60,
                                !locked,
                            ) {
                                changeDraft(draft.copy(connectTimeout = it))
                            }
                            NumberSetting(
                                tr(R.string.udp_idle_timeout_seconds),
                                draft.udpTimeout,
                                10..300,
                                !locked,
                            ) {
                                changeDraft(draft.copy(udpTimeout = it))
                            }
                            NumberSetting(
                                tr(R.string.max_active_connections),
                                draft.maxConnections,
                                16..512,
                                !locked,
                            ) {
                                changeDraft(draft.copy(maxConnections = it))
                            }
                        }
                        SettingsCard {
                            SettingField(tr(R.string.diagnostic_test_url), target, !locked) {
                                if (it.length <= 2048) target = it
                            }
                            SettingField(
                                tr(R.string.latency_target_empty_follows_group),
                                draft.delayUrl,
                                !locked,
                            ) {
                                if (it.length <= 2048) draft = draft.copy(delayUrl = it)
                            }
                            NumberSetting(
                                tr(R.string.test_timeout_milliseconds),
                                draft.probeTimeout,
                                1000..20000,
                                !locked,
                            ) {
                                changeDraft(draft.copy(probeTimeout = it))
                            }
                            NumberSetting(
                                tr(R.string.concurrent_tests),
                                draft.probeConcurrency,
                                1..16,
                                !locked,
                            ) {
                                changeDraft(draft.copy(probeConcurrency = it))
                            }
                            SettingSwitch(tr(R.string.unified_delay), draft.unifiedDelay, !locked) {
                                changeDraft(draft.copy(unifiedDelay = it))
                            }
                        }
                    }
                    "lan" ->
                        SettingsCard {
                            Choices(
                                tr(R.string.local_network),
                                draft.lan,
                                listOf(
                                    "proxy" to tr(R.string.proxy_lan),
                                    "direct" to tr(R.string.direct_lan),
                                    "block" to tr(R.string.block_lan),
                                ),
                                !locked,
                            ) {
                                changeDraft(draft.copy(lan = it))
                            }
                        }
                    "rules" -> {
                        SettingsCard {
                            ModeSegments(draft.mode, !locked) { changeDraft(draft.copy(mode = it)) }

                            SettingSwitch(
                                tr(R.string.include_provider_rules),
                                draft.useProviderRules,
                                !locked,
                            ) {
                                changeDraft(draft.copy(useProviderRules = it))
                            }
                            StatLine(tr(R.string.profiles), data.activeProfile?.name ?: "—")
                            TextButton(
                                { showProviderRules = true },
                                enabled = !data.activeProfile?.rules.isNullOrEmpty(),
                            ) {
                                Text((tr(R.string.view_provider_rules)))
                            }
                        }
                        SettingsCard {
                            OutlinedTextField(
                                draft.customRules,
                                {
                                    if (it.toByteArray().size <= 64 * 1024)
                                        draft = draft.copy(customRules = it)
                                },
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 200.dp, max = 400.dp)
                                    .testTag("custom-rules"),
                                enabled = !locked,
                                label = { Text((tr(R.string.custom_rules))) },
                                shape = UiCorners.Compact,
                            )
                        }
                    }
                    "apps" -> AppRoutingPanel(draft, !locked, heading) { changeDraft(it) }
                    "appearance" -> {
                        SettingsCard {
                            Choices(
                                tr(R.string.language),
                                data.client.language,
                                listOf(
                                    "system" to tr(R.string.system_default),
                                    "zh" to tr(R.string.text),
                                    "en" to tr(R.string.english),
                                ),
                                true,
                            ) {
                                model.appearance(it, data.client.theme)
                            }
                            // A solid background is always light, so it has no theme choice.
                            if (data.client.scene != "none")
                                ThemePicker(data.client.theme) {
                                    model.appearance(data.client.language, it)
                                }

                            SettingSwitch(
                                tr(R.string.ui_reduce_effects),
                                data.client.reduceEffects,
                                true,
                            ) {
                                model.appearance(
                                    data.client.language,
                                    data.client.theme,
                                    reduceEffects = it,
                                )
                            }

                            Choices(
                                tr(R.string.ui_scene),
                                data.client.scene,
                                listOf(
                                    "ocean" to tr(R.string.ui_scene_ocean),
                                    "clouds" to tr(R.string.ui_scene_clouds),
                                    "none" to tr(R.string.ui_scene_none),
                                ),
                                true,
                            ) {
                                model.appearance(
                                    data.client.language,
                                    data.client.theme,
                                    scenery = ScenePreferenceChange(scene = it),
                                )
                            }
                            if (data.client.scene == "none")
                                SolidColorPicker(
                                    data.client.solidColor,
                                    data.client.solidSwatches,
                                ) { color, swatches ->
                                    model.appearance(
                                        data.client.language,
                                        data.client.theme,
                                        scenery =
                                            ScenePreferenceChange(
                                                solidColor = color,
                                                solidSwatches = swatches,
                                            ),
                                    )
                                }
                            SettingSwitch(
                                tr(R.string.ui_reduce_transparency),
                                data.client.reduceTransparency,
                                true,
                            ) {
                                model.appearance(
                                    data.client.language,
                                    data.client.theme,
                                    scenery = ScenePreferenceChange(reduceTransparency = it),
                                )
                            }
                        }
                    }
                    "control" -> {
                        SettingsCard {
                            TextButton({
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                                }
                            }) {
                                Text((tr(R.string.system_vpn_settings)))
                            }
                            TextButton({
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(
                                                Settings.EXTRA_APP_PACKAGE,
                                                context.packageName,
                                            )
                                    )
                                }
                            }) {
                                Text(tr(R.string.notification_settings))
                            }
                            SettingSwitch(
                                tr(R.string.show_speed_in_notification),
                                draft.notificationSpeed,
                                appearanceEditable,
                            ) {
                                changeDraft(draft.copy(notificationSpeed = it))
                            }
                            TextButton({
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    )
                                }
                            }) {
                                Text((tr(R.string.system_battery_settings)))
                            }

                            TextButton({ reset = true }, enabled = !locked) {
                                Text((tr(R.string.restore_defaults)))
                            }
                        }
                    }
                    "privacy" -> {
                        SettingsCard {
                            SettingSwitch(
                                tr(R.string.show_destinations_session_memory_only),
                                draft.inspectConnections,
                                !locked,
                            ) {
                                changeDraft(draft.copy(inspectConnections = it))
                            }
                        }
                        SettingsCard {
                            SettingSwitch(
                                tr(R.string.keep_aggregate_usage_on_this_device),
                                draft.keepUsage,
                                appearanceEditable,
                            ) {
                                changeDraft(draft.copy(keepUsage = it))
                            }
                            OutlinedButton(model::resetUsage, enabled = !locked) {
                                Text(tr(R.string.clear_usage_totals))
                            }
                        }
                    }
                    "about" -> AboutPanel(model.nativeReady)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (confirmBack)
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            title = { Text(tr(R.string.settings_unsaved_title)) },
            text = { Text(tr(R.string.settings_unsaved_body)) },
            confirmButton = {
                TextButton({
                    draft = data.client
                    dns = data.dnsAddress
                    target = data.probeUrl
                    confirmBack = false
                    page = ""
                }) {
                    Text(tr(R.string.settings_discard))
                }
            },
            dismissButton = {
                TextButton({ confirmBack = false }) { Text(tr(R.string.settings_keep_editing)) }
            },
        )
    if (showProviderRules)
        AlertDialog(
            onDismissRequest = { showProviderRules = false },
            title = { Text(tr(R.string.view_provider_rules)) },
            text = {
                Text(
                    data.activeProfile?.rules?.joinToString("\n").orEmpty(),
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton({ showProviderRules = false }) { Text(tr(R.string.close)) }
            },
        )
    if (reset)
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text((tr(R.string.restore_defaults))) },
            text = { Text((tr(R.string.reset_network_display_preferences_per_app))) },
            confirmButton = {
                TextButton({
                    model.clientSettings(
                        ClientSettings(),
                        DEFAULT_DNS_ADDRESS,
                        "https://www.gstatic.com/generate_204",
                    )
                    reset = false
                }) {
                    Text((tr(R.string.restore_defaults)))
                }
            },
            dismissButton = { TextButton({ reset = false }) { Text((tr(R.string.cancel_2))) } },
        )
}

@Composable
internal fun Choices(
    title: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Text((title), color = Muted, style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (id, name) ->
            SegmentedButton(
                selected = value == id,
                onClick = { if (value != id) onChange(id) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                modifier = Modifier.testTag("choice-$title-$id"),
                // The filled segment marks the choice; a checkmark would cost a line wrap.
                icon = {},
            ) {
                // One line in every language: equal segments, shrink rather than wrap.
                val style = LocalTextStyle.current
                BasicText(
                    name,
                    style = style.copy(color = LocalContentColor.current),
                    maxLines = 1,
                    autoSize =
                        TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
                )
            }
        }
    }
}

@Composable
internal fun SettingField(
    title: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().testTag("field-$title"),
        enabled = enabled,
        singleLine = true,
        label = { Text((title)) },
        shape = UiCorners.Compact,
    )
}

@Composable
private fun NumberSetting(
    title: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val step = if (range.first == 1000 && range.last == 20000) 1000 else 1
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Muted)
        Text(position.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = position,
        onValueChange = { position = (it / step).toInt().times(step).coerceIn(range).toFloat() },
        onValueChangeFinished = {
            val next = position.toInt()
            if (next != value) onChange(next)
        },
        enabled = enabled,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.fillMaxWidth().testTag("number-$title"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsPanel(
    vpn: VpnUiState,
    model: AppViewModel,
    heading: @Composable () -> Unit,
) {
    // Pull down anywhere on the page to fetch the connections again at once.
    var revision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<ConnectionRow>()) }
    val foreground = rememberForeground()
    LaunchedEffect(vpn.phase, foreground, revision) {
        if (vpn.stage != VpnPhase.ESTABLISHED || !foreground) {
            rows = emptyList()
            refreshing = false
            return@LaunchedEffect
        }
        while (true) {
            rows =
                withContext(Dispatchers.IO) {
                    runCatching { parseConnectionRows(Mobile.vpnConnections()) }
                        .getOrDefault(emptyList())
                }
            refreshing = false
            delay(1000)
        }
    }
    PullToRefreshBox(
        refreshing,
        onRefresh = {
            refreshing = true
            revision++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("connections-list"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "heading") { heading() }
            item(key = "controls") { ConnectionControls(rows.size, vpn, model) }
            if (rows.isEmpty())
                item(key = "empty") { Text(tr(R.string.no_active_connections), color = Muted) }
            items(rows, key = { "connection:${it.id}" }) { r ->
                ConnectionListItem(r) {
                    model.closeConnection(r.id)
                    rows = rows.filterNot { it.id == r.id }
                }
            }
        }
    }
}

@Composable
private fun ConnectionControls(count: Int, vpn: VpnUiState, model: AppViewModel) {
    SettingsCard {
        StatLine(tr(R.string.active_connections), count.toString())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ model.closeConnection(0) }, enabled = count > 0) {
                Text(tr(R.string.close_all_connections))
            }
            OutlinedButton(model::restartVpn, enabled = vpn.stage == VpnPhase.ESTABLISHED) {
                Text(tr(R.string.reconnect))
            }
        }
    }
}

internal data class ConnectionRow(
    val id: Long,
    val network: String,
    val route: String,
    val destination: String,
    val seconds: Long,
    val up: Long,
    val down: Long,
    val ruleType: String = "",
)

@Composable
private fun AboutPanel(ready: Boolean) {
    val context = LocalContext.current
    var license by remember { mutableStateOf<String?>(null) }
    SettingsCard {
        Text("PingClash", style = MaterialTheme.typography.titleLarge)
        Text(buildIdentity() + " · GPL-3.0", color = Muted)
        StatLine(tr(R.string.proxy_core), if (ready) "mihomo v1.19.32" else "—")

        listOf("NOTICE.md", "GPL-3.0.txt", "Apache-2.0.txt").forEach { file ->
            TextButton({ license = file }) { Text(file) }
        }
    }
    CrashReportCard()
    license?.let { file ->
        val text =
            remember(file) {
                runCatching {
                        context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                    }
                    .getOrDefault(file)
            }
        AlertDialog(
            onDismissRequest = { license = null },
            title = { Text(file) },
            text = {
                Text(
                    text,
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton({ license = null }) { Text((tr(R.string.close))) } },
        )
    }
}
