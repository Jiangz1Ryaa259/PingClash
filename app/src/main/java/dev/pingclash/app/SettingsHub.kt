// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Intent
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                SettingsHeading(shown, textDirty, !locked, ::back) {
                    model.clientSettings(draft, dns.trim(), target.trim())
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
                val editor = SettingsDraft(draft, !locked, ::changeDraft) { draft = it }
                when (shown) {
                    "" ->
                        SettingsMenu(draft) { id ->
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
                    "dns" -> DnsPage(editor, dns) { dns = it }
                    "network" -> NetworkPage(editor, target) { target = it }
                    "lan" -> LanPage(editor)
                    "rules" -> RulesPage(editor, data.activeProfile) { showProviderRules = true }
                    "apps" -> AppRoutingPanel(draft, !locked, heading) { changeDraft(it) }
                    "appearance" -> AppearancePage(data, model)
                    "control" -> ControlPage(editor, appearanceEditable) { reset = true }
                    "automation" ->
                        AutomationPage(
                            SettingsDraft(draft, appearanceEditable, ::changeDraft) { draft = it },
                            appearanceEditable,
                        )
                    "backup" -> BackupPage(model, !uiBusy, vpn.active)
                    "privacy" -> PrivacyPage(editor, appearanceEditable, model::resetUsage)
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
    // Segments suit a short fixed set; a longer list opens as a menu instead.
    if (choices.size > 3) {
        PopUpChoice(title, value, choices, enabled, onChange)
        return
    }
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SegmentedControl(choices, value, enabled, { "choice-$title-$it" }, onChange)
    }
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
    SettingsSection {
        InfoRow(tr(R.string.active_connections), count.toString())
        RowDivider(underIcons = false)
        ActionRow(
            tr(R.string.reconnect),
            "connections-reconnect",
            enabled = vpn.stage == VpnPhase.ESTABLISHED,
            onClick = model::restartVpn,
        )
        RowDivider(underIcons = false)
        ActionRow(
            tr(R.string.close_all_connections),
            "connections-close-all",
            color = MaterialTheme.colorScheme.error,
            enabled = count > 0,
        ) {
            model.closeConnection(0)
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
    AboutHeader()
    SettingsSection { InfoRow(tr(R.string.proxy_core), if (ready) "mihomo v1.19.32" else "—") }
    SettingsSection(tr(R.string.ui_group_licenses)) {
        val files = listOf("NOTICE.md", "GPL-3.0.txt", "Apache-2.0.txt")
        files.forEachIndexed { index, file ->
            if (index > 0) RowDivider(underIcons = false)
            NavRow(file, "license-$file") { license = file }
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

/** The app's tile, name and build, centred at the top of About as on iOS. */
@Composable
private fun AboutHeader() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(76.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The launcher foreground is drawn on a 108 dp canvas around a 66 dp safe zone.
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                null,
                Modifier.requiredSize(124.dp),
            )
        }
        Text("PingClash", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            buildIdentity() + " · GPL-3.0",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
