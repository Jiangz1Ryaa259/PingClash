// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pingclash.diagnostics.Models
import dev.pingclash.diagnostics.ReportFormatter
import kotlinx.coroutines.launch

private const val SETTINGS_TAB = 3

@Composable
internal fun PingClashApp(model: AppViewModel = viewModel()) {
    val storedData by model.data.collectAsStateWithLifecycle()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // Remembered: a fresh copy per recomposition would drop AppData's cached selection.
    val data =
        remember(storedData, configuration) {
            if (Build.VERSION.SDK_INT >= 33)
                storedData.copy(client = storedData.client.copy(language = UiStrings.preference()))
            else storedData
        }
    // Renew just the collector on resume so its initial snapshot is the current service
    // projection, rather than a cached value from before the Activity stopped.
    val foreground = rememberForeground()
    val vpn by key(foreground) { model.vpn.collectAsStateWithLifecycle() }
    val ui by model.ui.collectAsStateWithLifecycle()
    val diagnosis by model.diagnosis.collectAsStateWithLifecycle()
    val tests by model.proxyTests.collectAsStateWithLifecycle()
    val serviceTests by model.serviceChecks.state.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableIntStateOf(1) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var diagnosisOrigin by rememberSaveable { mutableIntStateOf(SETTINGS_TAB) }
    val savedPages = rememberSaveableStateHolder()
    val savedSettingsRoutes = rememberSaveableStateHolder()
    fun openDiagnosis() {
        diagnosisOrigin = tab
        tab = SETTINGS_TAB
        tool = 0
    }
    fun closeDiagnosis() {
        if (diagnosisOrigin == SETTINGS_TAB) {
            tool = 1
        }
        tab = diagnosisOrigin
    }
    val notices = model.notices
    data.profiles.forEach { profile ->
        key(profile.id) {
            ProfileCompatibilityNotice(profile, notices)
            ProviderFetchNotice(profile, notices)
        }
    }
    SubscriptionExpiryNotice(data.activeProfile, notices)
    CrashReportNotice(notices)
    LaunchedEffect(ui.message) {
        ui.message?.let { message ->
            notices.publish(
                IssueNotice(
                    java.util.UUID.randomUUID().toString(),
                    tr(if (ui.messageWarning) R.string.issue_warning else R.string.issue_reminder),
                    message,
                    ui.messageStage,
                    if (ui.messageWarning) IssueSeverity.WARNING else IssueSeverity.REMINDER,
                )
            )
            model.dismissMessage()
        }
    }
    LaunchedEffect(vpn.phase, vpn.failureId, vpn.failureCode) {
        if (vpn.stage == VpnPhase.ERROR || vpn.failureId > 0)
            notices.publish(vpnIssue(vpn, "vpn:${vpn.failureId}:${vpn.failureCode}"))
    }
    val context = LocalContext.current
    val vpnPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                model.prepareVpnTransition()
                runCatching { VpnRuntime.connect(context) }
                    .onFailure { model.message(tr(R.string.android_did_not_allow_the_vpn_to)) }
            } else model.message(tr(R.string.vpn_permission_was_not_granted))
        }
    fun requestVpn() {
        model.prepareVpnTransition()
        val authorization = VpnService.prepare(context)
        if (authorization == null)
            runCatching { VpnRuntime.connect(context) }
                .onFailure { model.message(tr(R.string.android_did_not_allow_the_vpn_to)) }
        else vpnPermission.launch(authorization)
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpn()
        }
    val locked = ui.busy || vpn.active
    val motion = rememberMotionAllowed(data.client.reduceEffects)
    CompositionLocalProvider(
        LocalMotionAllowed provides motion,
        LocalIssueNotices provides notices,
        LocalSolidBackground provides solidColor(data.client.solidColor),
    ) {
        ScenicFrame(
            tab,
            data.client.scene,
            data.client.reduceTransparency,
            onSelect = { destination ->
                if (destination == 3 && tab != 3) {
                    tool = 1
                }
                tab = destination
            },
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                    AnimatedContent(
                        tab,
                        transitionSpec = {
                            if (motion && initialState != 0 && targetState != 0) {
                                val direction = if (targetState > initialState) 1 else -1
                                (slideInHorizontally(spring(dampingRatio = 1f, stiffness = 650f)) {
                                    it / 12 * direction
                                } + fadeIn(tween(190))) togetherWith
                                    (slideOutHorizontally(tween(140)) { -it / 16 * direction } +
                                        fadeOut(tween(110)))
                            } else if (motion) fadeIn(tween(190)) togetherWith fadeOut(tween(110))
                            else fadeIn(snap()) togetherWith fadeOut(snap())
                        },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)),
                        label = "main-navigation",
                    ) { page ->
                        savedPages.SaveableStateProvider(page) {
                            when (page) {
                                0 ->
                                    DashboardScreen(
                                        DashboardState(
                                            data,
                                            vpn,
                                            diagnosis,
                                            tests,
                                            model.nativeReady,
                                            ui.busy,
                                            serviceTests,
                                        ),
                                        DashboardActions(
                                            nodes = {
                                                tab = if (data.activeProfile == null) 2 else 1
                                            },
                                            diagnose = {
                                                openDiagnosis()
                                                model.diagnose()
                                            },
                                            test = {
                                                data.activeProfile?.let { profile ->
                                                    data.selected?.let {
                                                        model.testProxies(profile, listOf(it.name))
                                                    }
                                                }
                                            },
                                            mode = model::routeMode,
                                            selectProfile = model::activate,
                                            themeMode = {
                                                model.appearance(data.client.language, it)
                                            },
                                            serviceTest = { targets ->
                                                if (serviceTests.running && targets.size != 1)
                                                    model.serviceChecks.cancel()
                                                else {
                                                    model.serviceChecks.start(
                                                        data.client.probeTimeout,
                                                        targets,
                                                    )
                                                }
                                            },
                                            connect = {
                                                if (VpnRuntime.state.value.active) {
                                                    model.prepareVpnTransition()
                                                    VpnRuntime.disconnect(context)
                                                } else if (data.selected == null)
                                                    tab = if (data.activeProfile == null) 2 else 1
                                                else if (
                                                    Build.VERSION.SDK_INT >= 33 &&
                                                        context.checkSelfPermission(
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        ) != PackageManager.PERMISSION_GRANTED
                                                )
                                                    notificationPermission.launch(
                                                        Manifest.permission.POST_NOTIFICATIONS
                                                    )
                                                else requestVpn()
                                            },
                                        ),
                                    )
                                1 ->
                                    ProxyScreen(
                                        data,
                                        ui.busy ||
                                            diagnosis.running ||
                                            vpn.phase in setOf("connecting", "stopping"),
                                        model,
                                        tests,
                                        onProfiles = { tab = 2 },
                                        onDiagnosis = { openDiagnosis() },
                                    )
                                2 -> ProfilesScreen(data, locked, model, ui) { tab = 1 }
                                else ->
                                    SettingsSlide(if (tool == 0) "diagnosis" else "") { route ->
                                        savedSettingsRoutes.SaveableStateProvider(route) {
                                            if (route == "diagnosis")
                                                NodeDiagnosisScreen(
                                                    data,
                                                    diagnosis,
                                                    ui.busy,
                                                    model,
                                                ) {
                                                    closeDiagnosis()
                                                }
                                            else
                                                SettingsHub(
                                                    data,
                                                    vpn,
                                                    locked,
                                                    model,
                                                    onDiagnose = { openDiagnosis() },
                                                )
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        IssueNoticeHost(notices)
    }
    // Each dialog is its own composable. Inlined here, the last conditional got no group of
    // its own, so its remembered button lambda shared this function's slots with
    // rememberSaveable's holder; the first certificate notice then crashed with a
    // ClassCastException (SaveableHolder -> ComposableLambdaImpl).
    var resetDeclined by rememberSaveable { mutableStateOf(false) }
    if (data.loadFailed && !resetDeclined) UnreadableDataDialog(model) { resetDeclined = true }
    if (
        !data.loadFailed &&
            !data.certificateNoticeAcknowledged &&
            data.selected?.certificateVerificationSkipped == true
    )
        CertificateNoticeDialog(model)
}

@Composable
private fun UnreadableDataDialog(model: AppViewModel, decline: () -> Unit) {
    AlertDialog(
        onDismissRequest = decline,
        title = { Text(tr(R.string.local_data_unreadable_title)) },
        text = { Text(tr(R.string.local_data_unreadable_body)) },
        confirmButton = {
            TextButton(model::resetLocalData) { Text(tr(R.string.reset_local_data)) }
        },
        dismissButton = { TextButton(decline) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun CertificateNoticeDialog(model: AppViewModel) {
    AlertDialog(
        onDismissRequest = model::acknowledgeCertificateNotice,
        title = { Text((tr(R.string.node_certificate_policy))) },
        text = { Text(tr(R.string.some_nodes_skip_certificate_verification_s)) },
        confirmButton = {
            TextButton(onClick = model::acknowledgeCertificateNotice) {
                Text((tr(R.string.understood_do_not_remind_again)))
            }
        },
    )
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .padding(bottom = LocalNavigationClearance.current),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onBack,
                Modifier.testTag("diagnosis-back").semantics {
                    contentDescription = tr(R.string.back)
                },
            ) {
                AppGlyph(BACK_GLYPH)
            }
            PageHeading(title, Modifier.weight(1f))
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Tile(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = UiCorners.Card,
        border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun IconBadge(index: PcIcon) {
    Box(
        Modifier.size(44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AppGlyph(index, tint = Mint)
    }
}

@Composable
private fun ProfilesScreen(
    data: AppData,
    locked: Boolean,
    model: AppViewModel,
    ui: AppUiState,
    onProxies: () -> Unit,
) {
    val vpn by model.vpn.collectAsStateWithLifecycle()
    val refreshing by model.refreshingProfiles.collectAsStateWithLifecycle()
    // Downloads hot-apply to a running VPN; only a VPN transition or busy work blocks them.
    val fetchLocked = ui.busy || vpn.stage == VpnPhase.CONNECTING || vpn.stage == VpnPhase.STOPPING
    var uaProfile by remember { mutableStateOf<LocalProfile?>(null) }
    var uaText by remember { mutableStateOf(DEFAULT_SUBSCRIPTION_UA) }
    var rename by remember { mutableStateOf<LocalProfile?>(null) }
    var export by remember { mutableStateOf<LocalProfile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var exportId by remember { mutableStateOf<String?>(null) }
    val exportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val id = exportId
            exportId = null
            if (uri != null && id != null) model.exportProfile(id, uri)
        }
    val importContext = LocalContext.current
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val id = result.data?.getStringExtra(ProfileImportActivity.RESULT_PROFILE)
            if (result.resultCode == Activity.RESULT_OK && id != null) model.importCompleted(id)
        }
    var delete by remember { mutableStateOf<LocalProfile?>(null) }
    var details by remember { mutableStateOf<LocalProfile?>(null) }
    val commands: (ProfileCommand, LocalProfile) -> Unit = { command, profile ->
        when (command) {
            ProfileCommand.REFRESH -> model.refresh(profile)
            ProfileCommand.DETAILS -> details = profile
            ProfileCommand.RENAME -> {
                rename = profile
                renameText = profile.name
            }
            ProfileCommand.EXPORT -> export = profile
            ProfileCommand.DELETE -> delete = profile
            ProfileCommand.UA -> {
                uaProfile = profile
                uaText = profile.userAgent
            }
            ProfileCommand.REPARSE -> model.reparse(profile)
            ProfileCommand.COMPATIBILITY -> {
                model.notices.inspect(
                    IssueNotice(
                        "compatibility:${profile.id}:${compatibilityWarning(profile, null).hashCode()}",
                        tr(R.string.compatibility_warning),
                        compatibilityWarning(profile, null),
                        IssueStage.IMPORT,
                        IssueSeverity.WARNING,
                        "compatibility-warning",
                    )
                )
            }
            ProfileCommand.PROVIDERS -> model.refreshProviders(profile)
            ProfileCommand.PROXIES -> {
                if (profile.id != data.activeProfile?.id) model.activate(profile.id)
                onProxies()
            }
        }
    }
    CompositionLocalProvider(
        LocalProfileFetchLock provides fetchLocked,
        LocalProfileSelectionLock provides fetchLocked,
        LocalRefreshingProfiles provides refreshing,
    ) {
        ProfilesOverview(
            data,
            locked,
            ui,
            ProfileActions(
                select = { model.activate(it.id) },
                command = commands,
                add = {
                    importPicker.launch(
                        ProfileImportActivity.intent(importContext, ImportMethod.URL),
                        ProfileImportActivity.unfoldOptions(importContext),
                    )
                },
                import = {
                    importPicker.launch(
                        ProfileImportActivity.intent(importContext, it),
                        ProfileImportActivity.unfoldOptions(importContext),
                    )
                },
                refreshAll = model::refreshAll,
                reorder = model::reorderProfiles,
            ),
        )
        details?.let { profile ->
            ProfileDetailsSheet(
                data.profiles.firstOrNull { it.id == profile.id } ?: profile,
                locked,
                { details = null },
                commands,
            )
        }
    }
    uaProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { uaProfile = null },
            title = { Text(tr(R.string.subscription_ua)) },
            text = {
                OutlinedTextField(
                    uaText,
                    { if (it.length <= 256) uaText = it },
                    singleLine = true,
                    isError = !validSubscriptionUa(uaText),
                )
            },
            confirmButton = {
                TextButton(
                    {
                        model.subscriptionUserAgent(profile.id, uaText)
                        uaProfile = null
                    },
                    enabled = validSubscriptionUa(uaText),
                ) {
                    Text(tr(R.string.save))
                }
            },
            dismissButton = { TextButton({ uaProfile = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    rename?.let { profile ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text((tr(R.string.rename))) },
            text = {
                OutlinedTextField(
                    renameText,
                    { if (it.length <= 100) renameText = it },
                    singleLine = true,
                    label = { Text((tr(R.string.profile_name))) },
                )
            },
            confirmButton = {
                TextButton(
                    {
                        model.rename(profile.id, renameText)
                        rename = null
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text((tr(R.string.save)))
                }
            },
            dismissButton = { TextButton({ rename = null }) { Text((tr(R.string.cancel_2))) } },
        )
    }
    export?.let { profile ->
        AlertDialog(
            onDismissRequest = { export = null },
            title = { Text((tr(R.string.export_profile))) },
            text = { Text((tr(R.string.the_exported_file_contains_node_credential))) },
            confirmButton = {
                TextButton({
                    exportId = profile.id
                    export = null
                    exportPicker.launch("PingClash-profile.json")
                }) {
                    Text((tr(R.string.export_profile)))
                }
            },
            dismissButton = { TextButton({ export = null }) { Text((tr(R.string.cancel_2))) } },
        )
    }
    delete?.let { profile ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text((tr(R.string.delete_this_profile))) },
            text = {
                Text(
                    trPlural(
                        R.plurals.count_delete_saved_proxy_and_the_subscript,
                        (profile.nodes.size).toInt(),
                        profile.nodes.size,
                    )
                )
            },
            confirmButton = {
                TextButton({
                    model.delete(profile.id)
                    delete = null
                }) {
                    Text((tr(R.string.delete)), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ delete = null }) { Text((tr(R.string.cancel_2))) } },
        )
    }
}

@Composable
private fun NodeDiagnosisScreen(
    data: AppData,
    diagnosis: DiagnosticUiState,
    busy: Boolean,
    model: AppViewModel,
    onBack: () -> Unit,
) {
    var auxiliary by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val auxiliaryModel: DiagnosticViewModel = viewModel()
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf("") }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown")
        ) { uri ->
            if (uri != null) ReportExporter.save(context, uri, pending)
        }
    if (auxiliary) {
        AuxiliaryDiagnosticScreen(auxiliaryModel) { auxiliary = false }
        return
    }
    androidx.activity.compose.BackHandler(!auxiliary) { onBack() }
    val diagnosed = data.copy(selectedId = diagnosis.nodeId ?: data.selectedId).selected
    DiagnosticContextNotice(diagnosis.report?.runId ?: "node", diagnosis.steps, diagnosis.running)
    Page(tr(R.string.connection_diagnostics), onBack) {
        Tile {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(PcIcon.Diagnostics)
                Column(Modifier.weight(1f)) {
                    Text(
                        tr(R.string.proxy_diagnostic_target),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        diagnosed?.name ?: tr(R.string.choose_a_proxy_first),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("diagnostic-node-name"),
                    )
                }
            }
            Button(
                onClick = model::repeatDiagnosis,
                enabled = diagnosed != null && !diagnosis.running && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
            ) {
                Text(
                    if (diagnosis.running) tr(R.string.collecting_connection_evidence)
                    else tr(R.string.start_proxy_diagnostics)
                )
            }
            if (diagnosis.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = model::cancelDiagnosis) {
                    Text((tr(R.string.cancel_diagnostics)))
                }
            }
        }
        diagnosis.report?.let { report ->
            Tile {
                Text((tr(R.string.result)), color = Mint, fontWeight = FontWeight.Bold)
                DiagnosticConclusion(
                    report,
                    MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                )
                Text(
                    "${report.elapsedMs} ms · ${report.networkType}",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedButton(onClick = { preview = true }) {
                    Text((tr(R.string.preview_redacted_report)))
                }
            }
        }
        visibleDiagnosticSteps(diagnosis.steps)
            .filter { it.stage != Models.Stage.POLICY }
            .forEach { StepCard(it) }
        TextButton(
            onClick = { auxiliary = true },
            modifier = Modifier.testTag("diagnostic-auxiliary"),
        ) {
            Text(tr(R.string.check_a_domain_or_ip_manually))
        }
    }
    if (preview && diagnosis.report != null)
        ReportPreview(
            requireNotNull(diagnosis.report),
            onSave = { report ->
                pending = ReportFormatter.markdown(report)
                save.launch("PingClash-${report.runId.take(8)}.md")
            },
        ) {
            preview = false
        }
}

@Composable
internal fun ReportPreview(
    report: Models.Report,
    onSave: (Models.Report) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val markdown =
        remember(report, UiStrings.resources().configuration.locales.toLanguageTags()) {
            ReportFormatter.markdown(report)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((tr(R.string.redacted_report_preview))) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(markdown, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(report) }) { Text((tr(R.string.save_md))) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, markdown)
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        tr(R.string.pingclash_redacted_diagnostic_report),
                                    )
                                },
                                tr(R.string.share_diagnostic_report),
                            )
                        )
                    }
                }
            ) {
                Text((tr(R.string.share)))
            }
        },
    )
}

private fun bytes(value: Long): String =
    when {
        value >= 1024L * 1024 ->
            String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
        value >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }
