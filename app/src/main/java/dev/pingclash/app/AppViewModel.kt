// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pingclash.diagnostics.DiagnosticEngine
import dev.pingclash.diagnostics.Models
import dev.pingclash.diagnostics.ProbeFailure
import dev.pingclash.diagnostics.RunControl
import dev.pingclash.mobile.Mobile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class AppUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val importIssue: String? = null,
    val importedProfileId: String? = null,
    val messageWarning: Boolean = false,
    val messageStage: IssueStage = IssueStage.OPERATION,
)

data class ProxyDelay(
    val state: String,
    val elapsedMs: Long = 0,
    val completed: Int = 0,
    val total: Int = 0,
    val passed: Int = 0,
)

data class ProxyTestState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val values: Map<String, ProxyDelay> = emptyMap(),
    val tcpValues: Map<String, ProxyDelay> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val kind: String = "url",
)

internal fun delayKey(profileId: String, name: String, group: String = "") =
    "$profileId\u0000$group\u0000$name"

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PingClashApplication
    val data = app.repository.data
    val vpn = VpnRuntime.state
    val refreshingProfiles = app.repository.refreshing
    val nativeReady
        get() = app.nativeReady

    private val mutableUi = MutableStateFlow(AppUiState())
    val ui = mutableUi.asStateFlow()
    internal val notices = IssueNoticeCenter()
    private val mutableDiagnosis = MutableStateFlow(DiagnosticUiState())
    val diagnosis = mutableDiagnosis.asStateFlow()
    private val mutableProxyTests = MutableStateFlow(ProxyTestState())
    val proxyTests = mutableProxyTests.asStateFlow()
    @Volatile private var proxyEnvironment: ProxyEnvironment? = null
    internal val serviceChecks =
        ServiceChecks(viewModelScope, app) {
            viewModelScope.launch { invalidateProxyEnvironment() }
        }
    private var testJob: Job? = null
    private val proxyGeneration = AtomicInteger()
    private val proxyVersion
        get() = proxyGeneration.get()

    @Volatile private var chainedBatch = false
    private val memberTcpJobs = ConcurrentHashMap<String, Job>()
    private val tcpRuns = ConcurrentHashMap.newKeySet<RunControl>()
    private val testRuns = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var control: RunControl? = null
    @Volatile private var nativeRun: String? = null

    private fun requestKey(data: AppData) = websiteRequestKey(data)

    init {
        if (data.value.loadRepaired) message(tr(R.string.local_data_repaired))
        viewModelScope.launch {
            vpn.map { it.phase to it.sessionStartedElapsed }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    // A probe from a previous VPN session must not look like a live
                    // indication of the current system tunnel.
                    serviceChecks.cancel()
                    invalidateProxyEnvironment()
                    cancelDiagnosis()
                }
        }
        viewModelScope.launch {
            data.map(::requestKey).distinctUntilChanged().drop(1).collect {
                // Independent node measurements are still valid when the chosen route changes.
                // A real dialer-chain snapshot depends on those choices, so cancel that batch.
                if (chainedBatch && mutableProxyTests.value.running) cancelProxyTests()
                serviceChecks.cancel()
                if (mutableDiagnosis.value.running) cancelDiagnosis()
                else mutableDiagnosis.value = DiagnosticUiState()
            }
        }
        viewModelScope.launch {
            var before = data.value
            data.map(::ProxyTestInputs).distinctUntilChanged().drop(1).collect {
                cancelProxyTests()
                // A profile update keeps every result whose node is unchanged; only removed
                // or edited nodes, or different test settings, lose theirs.
                val after = data.value
                mutableProxyTests.update { state ->
                    state.copy(
                        values = state.values.filterKeys { delayStillValid(it, before, after) },
                        tcpValues =
                            state.tcpValues.filterKeys { delayStillValid(it, before, after) },
                    )
                }
                before = after
            }
        }
    }

    fun dismissMessage() {
        mutableUi.update { it.copy(message = null) }
    }

    fun acknowledgeCertificateNotice() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.repository.acknowledgeCertificateNotice() }
                .onFailure { message(tr(R.string.could_not_save_the_notice_preference_try)) }
        }
    }

    fun message(text: String, warning: Boolean = true) {
        mutableUi.update {
            it.copy(message = text, messageWarning = warning, messageStage = IssueStage.OPERATION)
        }
    }

    private fun operation(block: () -> String) {
        if (!app.nativeReady) return message(tr(R.string.the_native_core_could_not_load_install))
        // A refresh can run for a minute; say why a tap did nothing.
        if (mutableUi.value.busy)
            return message(tr(R.string.operation_in_progress), warning = false)
        mutableUi.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            var warning = false
            var stage = IssueStage.OPERATION
            val message =
                try {
                    block()
                } catch (failure: Exception) {
                    warning = true
                    stage = failureStage(failure)
                    publicFailure(failure)
                }
            mutableUi.update {
                it.copy(
                    busy = false,
                    message = message.takeIf { warning && it.isNotBlank() },
                    messageWarning = warning,
                    messageStage = stage,
                )
            }
        }
    }

    internal fun publicFailure(failure: Exception): String =
        when (failure) {
            is ProviderFailure -> failure.publicMessage()
            is SubscriptionTransferFailure -> failure.publicMessage()
            is ProfileImportFailure -> failure.publicMessage
            is SettingsFailure -> failure.publicMessage
            is SubscriptionHttpFailure ->
                tr(R.string.subscription_server_returned_http_check_th, failure.status)
            is java.net.UnknownHostException ->
                tr(R.string.the_subscription_hostname_could_not_resolv)
            is java.net.SocketTimeoutException ->
                tr(R.string.subscription_download_timed_out_try_anothe)
            is javax.net.ssl.SSLException -> tr(R.string.subscription_tls_handshake_failed_check_th)
            else ->
                failure.message?.takeIf {
                    it in
                        setOf(
                            tr(R.string.enter_a_profile_name_of_1_100),
                            tr(R.string.invalid_configuration_format_or_size_limit),
                            tr(R.string.no_supported_proxies_check_protocols_and_c),
                            tr(R.string.a_maximum_of_20_profiles_can_be),
                            tr(R.string.local_profile_storage_limit_exceeded),
                            tr(R.string.content_exceeds_the_size_limit),
                            tr(R.string.local_profiles_could_not_be_read_the),
                            tr(R.string.disconnect_the_vpn_before_changing_profile),
                            tr(R.string.enter_a_dns_ip_address_with_an),
                            tr(R.string.dns_requires_a_valid_ip_address_with),
                            tr(R.string.the_test_target_must_be_an_https),
                            tr(R.string.subscriptions_require_an_https_link_withou),
                            tr(R.string.the_latency_target_must_be_an_http),
                            tr(R.string.unsupported_subscription_redirect_address),
                            tr(R.string.subscription_redirect_is_missing_its_desti),
                            tr(R.string.too_many_subscription_redirects),
                            tr(R.string.subscription_download_timed_out),
                            tr(R.string.network_options_are_invalid_or_exceed_limi),
                            tr(R.string.invalid_app_routing_list_include_mode_requ),
                            tr(R.string.display_or_latency_options_are_invalid_or),
                            tr(R.string.original_rules_are_invalid_or_exceed_limit),
                        )
                } ?: tr(R.string.operation_incomplete_check_the_configurati)
        }

    private val mutableExit = MutableStateFlow(ExitState())
    /** The address the internet sees from this phone right now; in memory only. */
    internal val exit = mutableExit.asStateFlow()
    private var exitJob: Job? = null

    /**
     * Asks which address the internet sees, over the route traffic really takes now, and classifies
     * it with the bundled data. One check at a time; a failure keeps the last result for that
     * route.
     */
    fun refreshExit(force: Boolean = false) {
        val route = exitRoute(vpn.value, data.value)
        if (!app.nativeReady || route == null || exitJob?.isActive == true) return
        // Coming back to the app re-checks at most once a minute; the refresh button always does.
        val last = mutableExit.value.info
        val fresh =
            last?.route == route && System.currentTimeMillis() - last.checkedAt < EXIT_FRESH_MS
        // Automatic checks never compete with latency tests or a diagnosis.
        val measuring = mutableProxyTests.value.running || mutableDiagnosis.value.running
        if (!force && (fresh || measuring)) return
        val main = data.value.selected
        mutableExit.update { it.copy(checking = true) }
        exitJob =
            viewModelScope.launch(Dispatchers.IO) {
                val info =
                    runCatching {
                            // Right after a switch, ask once the new route is in place.
                            VpnRuntime.awaitRouting()
                            val reply = Mobile.exitDetails(fetchExitTrace(EXIT_TIMEOUT_MS))
                            ExitInfo.read(
                                JSONObject(reply),
                                route,
                                main,
                                System.currentTimeMillis(),
                            )
                        }
                        .getOrNull()
                        // The route changed while checking: this answer belongs to the old one.
                        ?.takeIf { exitRoute(vpn.value, data.value) == route }
                mutableExit.update { previous ->
                    ExitState(
                        info ?: previous.info?.takeIf { it.route == route },
                        checking = false,
                        failed = info == null,
                    )
                }
            }
    }

    fun importCompleted(id: String) {
        val profile = data.value.profiles.firstOrNull { it.id == id } ?: return
        val detail = compatibilityWarning(profile, null)
        if (detail.isNotBlank())
            notices.publish(
                IssueNotice(
                    "compatibility:${profile.id}:${detail.hashCode()}",
                    tr(R.string.compatibility_warning),
                    detail,
                    IssueStage.IMPORT,
                    IssueSeverity.WARNING,
                    "compatibility-warning",
                )
            )
    }

    fun clearImportIssue() {
        mutableUi.update { it.copy(importIssue = null) }
    }

    private fun importOperation(block: (kotlinx.coroutines.Job) -> LocalProfile) {
        if (!app.nativeReady) {
            mutableUi.update {
                it.copy(importIssue = tr(R.string.the_native_core_could_not_load_reinstall))
            }
            return
        }
        if (mutableUi.value.busy) {
            mutableUi.update { it.copy(importIssue = tr(R.string.operation_in_progress)) }
            return
        }
        mutableUi.update { it.copy(busy = true, message = null, importIssue = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile =
                    block(
                        requireNotNull(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job])
                    )
                kotlin.coroutines.coroutineContext.ensureActive()
                mutableUi.update {
                    it.copy(
                        busy = false,
                        message = null,
                        importedProfileId = profile.id,
                        messageWarning = false,
                        messageStage = IssueStage.IMPORT,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUi.update {
                    it.copy(
                        busy = false,
                        importIssue = publicFailure(failure),
                        messageWarning = true,
                        messageStage = failureStage(failure),
                    )
                }
            }
        }
    }

    fun importText(name: String, text: String) = importOperation { job ->
        prepareImport(job, name.ifBlank { tr(R.string.local_profile) }, text)
    }

    fun importFile(name: String, uri: Uri) = importOperation { job ->
        val document =
            app.contentResolver.openInputStream(uri)?.use { ProfileDocument.read(it) }
                ?: error("input_unavailable")
        val filename =
            runCatching {
                    app.contentResolver
                        .query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }
                .getOrNull()
        prepareImport(
            job,
            SubscriptionNames.choose(
                name,
                filename?.let(SubscriptionNames::file),
                tr(R.string.local_profile),
            ),
            document.content,
            localBodies = document.localBodies,
        )
    }

    fun subscribe(name: String, source: String, userAgent: String = DEFAULT_SUBSCRIPTION_UA) =
        importOperation { job ->
            val input = requireNotNull(subscriptionInput(source))
            val response = SubscriptionDownloader.fetchResponse(input.source, userAgent)
            val title =
                SubscriptionNames.choose(
                    name,
                    input.suggestedName.ifBlank { response.suggestedName.orEmpty() },
                    tr(R.string.my_subscription),
                )
            prepareImport(job, title, response.content, input.source, userAgent, response)
        }

    @Suppress("LongParameterList") // One atomic import transaction, with its cancellation owner.
    private fun prepareImport(
        job: kotlinx.coroutines.Job,
        name: String,
        text: String,
        source: String? = null,
        userAgent: String = DEFAULT_SUBSCRIPTION_UA,
        response: SubscriptionResponse? = null,
        localBodies: Map<String, String> = emptyMap(),
    ): LocalProfile {
        job.ensureActive()
        val prepared = RemoteProfiles.loadDocument(ProfileDocument(text, localBodies), userAgent)
        job.ensureActive()
        return app.repository.importProfile(
            name,
            prepared.content,
            source,
            userAgent = userAgent,
            subscriptionResponse = response,
            remote = prepared.remote,
        )
    }

    fun refresh(profile: LocalProfile) = operation {
        if (vpn.value.active) {
            // Connected: update through the hot routing path instead of reconnecting.
            app.repository.refreshSubscription(profile, forceProviders = true)
            return@operation data.value.profiles
                .firstOrNull { it.id == profile.id }
                ?.let(::imported)
                .orEmpty()
        }
        val source = requireNotNull(profile.source)
        app.repository.trackRefresh(profile.id) {
            val response = SubscriptionDownloader.fetchResponse(source, profile.userAgent)
            imported(
                app.repository.importWithProviders(
                    profile.name,
                    response.content,
                    source,
                    profile.id,
                    userAgent = profile.userAgent,
                    response = response,
                )
            )
        }
    }

    fun refreshAll() = operation {
        val (updated, failed) = app.repository.refreshAllSubscriptions()
        when {
            updated + failed == 0 -> tr(R.string.subscriptions_none_to_update)
            failed == 0 -> tr(R.string.subscriptions_all_updated, updated)
            else ->
                throw ProfileImportFailure(
                    tr(R.string.subscriptions_partly_updated, updated, failed)
                )
        }
    }

    fun reorderProfiles(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.repository.reorderProfiles(ids) }
                .onFailure { message(tr(R.string.local_profiles_could_not_be_read_the)) }
        }
    }

    fun refreshProviders(profile: LocalProfile) = operation {
        app.repository.refreshProviders(profile, true)
        // While connected the bodies are stored as pending; apply them live.
        app.repository.applyPendingProvidersLive()
        ""
    }

    fun reparse(profile: LocalProfile) = operation { imported(app.repository.reparse(profile.id)) }

    private fun imported(profile: LocalProfile): String =
        trPlural(
            R.plurals.count_imported_proxy_imported_proxies,
            (profile.nodes.size).toInt(),
            profile.nodes.size,
        ) +
            (if (profile.unsupported > 0)
                tr(R.string.configurations_not_imported, profile.unsupported)
            else "")

    private fun selection(block: () -> Unit) = operation {
        if (vpn.value.stage == VpnPhase.ESTABLISHED) VpnRuntime.reconfigure(app, block) else block()
        ""
    }

    fun select(id: String) = selection { app.repository.select(id) }

    fun activate(profileId: String) = selection { app.repository.activate(profileId) }

    fun selectGroup(profile: LocalProfile, group: LocalGroup, choice: String) = selection {
        app.repository.selectGroup(profile.id, group.name, choice)
    }

    fun toggleGroupMember(profileId: String, groupName: String, name: String) = selection {
        val current = data.value
        val profile = current.profiles.single { it.id == profileId }
        val group = profile.groups.single { it.name == groupName }
        app.repository.selectGroup(
            profile.id,
            groupName,
            if (group.type != "select" && group.selected == name) "" else name,
        )
    }

    fun selectMainGroup(profile: LocalProfile, group: LocalGroup) =
        select(profile.groupId(group.name))

    fun delete(id: String) = operation {
        app.repository.delete(id)
        tr(R.string.profile_deleted)
    }

    fun settings(dns: String, target: String) = operation {
        app.repository.settings(dns, target)
        tr(R.string.settings_saved)
    }

    fun clientSettings(value: ClientSettings, dns: String, target: String) = operation {
        app.repository.clientSettings(value, dns, target)
        ""
    }

    // Settings changes are small encrypted saves. They run in order on their own queue,
    // without the global busy flag: the page's controls stay enabled while one is written,
    // and a quick second change is queued instead of rejected (which left the screen showing
    // a choice that was never stored).
    private val settingsWrites = Channel<Pair<ClientSettings, ClientSettings>>(Channel.UNLIMITED)
    private val pendingSettings = MutableStateFlow(0)
    val settingsPending = pendingSettings.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for ((before, after) in settingsWrites) {
                try {
                    app.repository.patchSettings(before, after)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    message(publicFailure(failure))
                } finally {
                    pendingSettings.update { it - 1 }
                }
            }
        }
    }

    fun patchSettings(before: ClientSettings, after: ClientSettings) {
        pendingSettings.update { it + 1 }
        settingsWrites.trySend(before to after)
    }

    fun routeMode(mode: String) = operation {
        val save = { app.repository.routeMode(mode) }
        if (vpn.value.stage == VpnPhase.ESTABLISHED) VpnRuntime.reconfigure(app, save) else save()
        ""
    }

    /** Saves service routing and blocking; a connected VPN applies them at once. */
    fun serviceRouting(routes: Map<String, String>, blocking: String) {
        runCatching { app.repository.serviceRouting(routes, blocking) }
            .onFailure { message(it.message ?: tr(R.string.vpn_update_failed)) }
    }

    fun appearance(
        language: String,
        theme: String,
        dynamicColor: Boolean? = null,
        reduceEffects: Boolean? = null,
        scenery: ScenePreferenceChange? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                    app.repository.appearance(language, theme, dynamicColor, reduceEffects, scenery)
                }
                .onFailure { message(tr(R.string.could_not_save_settings)) }
        }
    }

    private val editingBackground = MutableStateFlow(false)
    /** True while the picture framing editor is open. */
    val backgroundEditing = editingBackground.asStateFlow()

    fun editBackground(open: Boolean) {
        editingBackground.value = open
    }

    private val stagedBackground = MutableStateFlow<BackgroundFrame?>(null)
    /** A picked picture waiting for Done in the editor, with its starting framing. */
    internal val backgroundStaged = stagedBackground.asStateFlow()

    /**
     * Checks a picked image and opens the framing editor on it. The current background stays until
     * Done; failures and Cancel keep it.
     */
    fun importBackground(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stagedBackground.value = CustomBackground.stage(app, uri)
                editingBackground.value = true
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (rejected: BackgroundImportException) {
                message(backgroundRejectionMessage(rejected.reason))
            } catch (_: Exception) {
                message(backgroundRejectionMessage(BackgroundRejection.UNREADABLE))
            }
        }
    }

    /** Done in the editor: saves the framing; a picked picture also becomes the background. */
    internal fun saveBackground(frame: BackgroundFrame) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                    if (stagedBackground.value == null) BackgroundFrames.save(app, frame)
                    else {
                        CustomBackground.commit(app, frame)
                        stagedBackground.value = null
                        val client = app.repository.data.value.client
                        app.repository.appearance(
                            client.language,
                            client.theme,
                            scenery = ScenePreferenceChange(scene = "image"),
                        )
                    }
                }
                .onFailure { message(backgroundRejectionMessage(BackgroundRejection.UNREADABLE)) }
            editingBackground.value = false
        }
    }

    /** Cancel in the editor: a picked picture is thrown away and nothing else changes. */
    fun cancelBackground() {
        editingBackground.value = false
        if (stagedBackground.value == null) return
        stagedBackground.value = null
        viewModelScope.launch(Dispatchers.IO) { CustomBackground.discard(app) }
    }

    /** Deletes the stored picture and returns to the default scene. */
    fun removeBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                    CustomBackground.clear(app)
                    val client = app.repository.data.value.client
                    app.repository.appearance(
                        client.language,
                        client.theme,
                        scenery = ScenePreferenceChange(scene = "ocean"),
                    )
                }
                .onFailure { message(tr(R.string.could_not_save_settings)) }
        }
    }

    fun resetLocalData() = operation {
        app.repository.resetLocalData()
        tr(R.string.local_data_reset_done)
    }

    fun resetUsage() = operation {
        app.repository.resetUsage()
        tr(R.string.local_usage_totals_cleared)
    }

    fun subscriptionUserAgent(id: String, value: String) = operation {
        app.repository.subscriptionUserAgent(id, value)
        ""
    }

    fun rename(id: String, name: String) = operation {
        app.repository.rename(id, name)
        tr(R.string.profile_name_updated)
    }

    fun closeConnection(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { Mobile.closeVPNConnection(id) }
    }

    fun restartVpn() {
        if (mutableUi.value.busy || vpn.value.stage != VpnPhase.ESTABLISHED) return
        prepareVpnTransition()
        mutableUi.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                    VpnRuntime.disconnect(app)
                    withTimeout(10000) { vpn.first { !it.active } }
                    VpnRuntime.connect(app)
                }
                .onFailure { message(tr(R.string.reconnect_did_not_complete_connect_again_f)) }
            mutableUi.update { it.copy(busy = false) }
        }
    }

    fun exportProfile(id: String, uri: Uri) = operation {
        val profile = data.value.profiles.single { it.id == id }
        val bytes = configurationExport(profile).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EXPORTED_PROFILE_BYTES)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("export_unavailable")
        tr(R.string.configuration_exported_the_file_contains_p)
    }

    /** Saves settings (and, when asked, profiles with their links) to a file the user chose. */
    fun exportBackup(uri: Uri, withProfiles: Boolean) = noticeOperation {
        val bytes = SettingsBackup.export(data.value, withProfiles).toByteArray(Charsets.UTF_8)
        require(bytes.size <= SettingsBackup.MAX_BYTES)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("export_unavailable")
        tr(if (withProfiles) R.string.ui_backup_saved_private else R.string.ui_backup_saved)
    }

    /**
     * Restores settings from a backup, then adds its profiles that are not here yet: subscriptions
     * are downloaded again, local profiles imported from their configuration.
     */
    fun restoreBackup(uri: Uri) = noticeOperation {
        val text =
            app.contentResolver.openInputStream(uri)?.use(SettingsBackup::readFile)
                ?: error("input_unavailable")
        val backup = SettingsBackup.read(text)
        if (vpn.value.active)
            throw SettingsFailure(tr(R.string.disconnect_the_vpn_before_changing_profile))
        app.repository.clientSettings(backup.settings, backup.dns, backup.probe)
        val (added, failed) = restoreProfiles(backup.profiles)
        when {
            added + failed == 0 -> tr(R.string.ui_backup_restored)
            failed == 0 -> tr(R.string.ui_backup_restored_profiles, added)
            else ->
                throw ProfileImportFailure(tr(R.string.ui_backup_restored_partly, added, failed))
        }
    }

    private fun restoreProfiles(profiles: List<BackupProfile>): Pair<Int, Int> {
        val present = data.value.profiles
        var added = 0
        var failed = 0
        profiles
            .filterNot { entry ->
                present.any {
                    entry.subscription != null && it.source == entry.subscription ||
                        entry.configuration != null && it.source == null && it.name == entry.name
                }
            }
            .forEach { entry ->
                runCatching {
                        val link = entry.subscription
                        if (link != null) {
                            val response =
                                SubscriptionDownloader.fetchResponse(link, entry.userAgent)
                            app.repository.importWithProviders(
                                entry.name,
                                response.content,
                                link,
                                userAgent = entry.userAgent,
                                response = response,
                            )
                        } else
                            app.repository.importWithProviders(
                                entry.name,
                                requireNotNull(entry.configuration),
                            )
                    }
                    .onSuccess { added++ }
                    .onFailure { failed++ }
            }
        return added to failed
    }

    /** Like [operation], and also shows the result when it succeeded. */
    private fun noticeOperation(block: () -> String) {
        if (!app.nativeReady) return message(tr(R.string.the_native_core_could_not_load_install))
        if (mutableUi.value.busy)
            return message(tr(R.string.operation_in_progress), warning = false)
        mutableUi.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val (text, warning) =
                try {
                    block() to false
                } catch (failure: Exception) {
                    publicFailure(failure) to true
                }
            mutableUi.update {
                it.copy(
                    busy = false,
                    message = text.takeIf(String::isNotBlank),
                    messageWarning = warning,
                    messageStage = IssueStage.OPERATION,
                )
            }
        }
    }

    fun testProxies(profile: LocalProfile, names: List<String>, groupName: String = "") {
        if (
            !app.nativeReady ||
                testJob?.isCompleted == false ||
                mutableProxyTests.value.running ||
                mutableUi.value.busy ||
                mutableDiagnosis.value.running
        )
            return
        proxyEnvironment = currentProxyEnvironment()
        // Keep only names in the queue. A profile definition may be 2 MiB;
        // materializing an envelope for every member would multiply that by 500.
        val plan = ProxyDelayPlan(profile, names, groupName)
        val candidates = plan.names
        chainedBatch = plan.chained.isNotEmpty()
        if (candidates.isEmpty()) {
            mutableProxyTests.update {
                it.copy(
                    completed = 0,
                    total = 0,
                    values = it.values + plan.summaryValues(emptyMap(), false),
                )
            }
            return
        }
        val version = proxyVersion
        val settings = data.value.client
        val target =
            settings.delayUrl.ifBlank {
                runCatching {
                        val raw = JSONObject(profile.definition).optJSONArray("proxy-groups")
                        if (raw == null) ""
                        else
                            (0 until raw.length())
                                .mapNotNull { raw.optJSONObject(it) }
                                .firstOrNull { it.optString("name") == groupName }
                                ?.optString("url")
                                .orEmpty()
                    }
                    .getOrDefault("")
                    .ifBlank { data.value.probeUrl }
            }
        val results = ConcurrentHashMap<String, ProxyDelay>()
        val pending = ConcurrentHashMap<String, ProxyDelay>()
        val completed = AtomicInteger(0)
        val flushLock = Any()
        fun flush() {
            synchronized(flushLock) {
                if (proxyVersion != version || !mutableProxyTests.value.running) return@synchronized
                val entries = pending.entries.map { it.key to it.value }
                entries.forEach { (key, value) -> pending.remove(key, value) }
                if (entries.isNotEmpty())
                    mutableProxyTests.update {
                        if (proxyVersion == version && it.running)
                            it.copy(
                                completed = completed.get(),
                                values = it.values + entries + plan.summaryValues(results, true),
                            )
                        else it
                    }
            }
        }
        val queued = candidates.associate { plan.key(it) to ProxyDelay("queued") }
        mutableProxyTests.update {
            it.copy(
                running = true,
                completed = 0,
                total = candidates.size,
                kind = "url",
                values = it.values + queued + plan.summaryValues(emptyMap(), true),
                aliases = it.aliases + plan.aliases,
            )
        }
        testJob =
            viewModelScope.launch(Dispatchers.IO) {
                val updates = launch {
                    while (true) {
                        delay(100)
                        flush()
                    }
                }
                try {
                    val retry = ConcurrentHashMap.newKeySet<String>()
                    // Early exits reject unavailable configuration/reservations and defer failed
                    // scans.
                    @Suppress("ReturnCount")
                    fun runProbe(name: String, timeout: Int, finalAttempt: Boolean) {
                        val key = plan.key(name)
                        val config = profile.proxyConfig(name)
                        if (config == null) {
                            val result = ProxyDelay("failed")
                            results[name] = result
                            pending[key] = result
                            completed.incrementAndGet()
                            return
                        }
                        val id = UUID.randomUUID().toString()
                        if (!Mobile.prepareProbe(id, timeout + 1000L)) {
                            completed.incrementAndGet()
                            val result = ProxyDelay("busy")
                            results[name] = result
                            pending[key] = result
                            return
                        }
                        testRuns.add(id)
                        try {
                            if (proxyVersion != version || !mutableProxyTests.value.running) {
                                Mobile.cancel(id)
                                return
                            }
                            results[name] = ProxyDelay("running")
                            pending[key] = ProxyDelay("running")
                            val reply =
                                JSONObject(
                                    Mobile.probeDelay(
                                        config,
                                        target,
                                        id,
                                        timeout.toLong(),
                                        settings.unifiedDelay,
                                    )
                                )
                            if (proxyVersion == version && mutableProxyTests.value.running) {
                                val state =
                                    when (reply.optString("code")) {
                                        "proxy_delay_measured" -> "passed"
                                        "cancelled" -> "idle"
                                        "timeout_stage_unknown",
                                        "proxy_channel_timeout",
                                        "proxy_quic_handshake_timeout" -> "timeout"
                                        else -> "failed"
                                    }
                                if (state in setOf("failed", "timeout") && !finalAttempt) {
                                    retry.add(name)
                                    val queued = ProxyDelay("queued")
                                    results[name] = queued
                                    pending[key] = queued
                                    return
                                }
                                completed.incrementAndGet()
                                val result = ProxyDelay(state, reply.optLong("elapsedMs"))
                                results[name] = result
                                pending[key] = result
                            }
                        } finally {
                            testRuns.remove(id)
                            Mobile.discardProbe(id)
                        }
                    }
                    val fastTimeout = minOf(settings.probeTimeout, FAST_SCAN_TIMEOUT)
                    val fast = fastTimeout < settings.probeTimeout
                    runDelayPlan(
                        plan,
                        settings.probeConcurrency.coerceAtMost(MAX_BATCH_DELAY_WORKERS),
                        { mutableProxyTests.value.running && proxyVersion == version },
                    ) {
                        runProbe(it, fastTimeout, !fast)
                    }
                    if (fast && retry.isNotEmpty()) {
                        val retryPlan =
                            ProxyDelayPlan(profile, plan.names.filter { it in retry }, groupName)
                        runDelayPlan(
                            retryPlan,
                            settings.probeConcurrency.coerceAtMost(MAX_BATCH_DELAY_WORKERS),
                            { mutableProxyTests.value.running && proxyVersion == version },
                        ) {
                            runProbe(it, settings.probeTimeout, true)
                        }
                    }
                } finally {
                    updates.cancel()
                    synchronized(flushLock) {
                        flush()
                        mutableProxyTests.update {
                            if (proxyVersion == version)
                                it.copy(
                                    running = false,
                                    values =
                                        it.values.filterValues { v ->
                                            v.state !in setOf("running", "queued")
                                        } + plan.summaryValues(results, false),
                                )
                            else it
                        }
                    }
                }
            }
    }

    fun testTcpProxies(profile: LocalProfile, names: List<String>, groupName: String = "") {
        if (testJob?.isCompleted == false || mutableUi.value.busy || mutableDiagnosis.value.running)
            return
        proxyEnvironment = currentProxyEnvironment()
        chainedBatch = false
        val candidates = names.distinct().take(MAX_PROFILE_NODES)
        if (candidates.isEmpty()) return
        val version = proxyVersion
        val settings = data.value.client
        val pending = ConcurrentHashMap<String, ProxyDelay>()
        val completed = AtomicInteger(0)
        val queue = AtomicInteger(0)
        fun flush() {
            if (proxyVersion != version || !mutableProxyTests.value.running) return
            val entries = pending.entries.map { it.key to it.value }
            entries.forEach { (key, value) -> pending.remove(key, value) }
            if (entries.isNotEmpty())
                mutableProxyTests.update {
                    if (proxyVersion == version && it.running)
                        it.copy(completed = completed.get(), tcpValues = it.tcpValues + entries)
                    else it
                }
        }
        mutableProxyTests.update {
            it.copy(running = true, completed = 0, total = candidates.size, kind = "tcp")
        }
        testJob =
            viewModelScope.launch(Dispatchers.IO) {
                val updates = launch {
                    while (true) {
                        delay(100)
                        flush()
                    }
                }
                try {
                    List(settings.probeConcurrency.coerceIn(1, 16)) {
                            async {
                                while (mutableProxyTests.value.running && proxyVersion == version) {
                                    val index = queue.getAndIncrement()
                                    if (index >= candidates.size) break
                                    val name = candidates[index]
                                    val key = delayKey(profile.id, name, groupName)
                                    val run = RunControl(settings.probeTimeout.toLong())
                                    tcpRuns.add(run)
                                    try {
                                        if (
                                            proxyVersion != version ||
                                                !mutableProxyTests.value.running
                                        ) {
                                            run.cancel()
                                            break
                                        }
                                        pending[key] = ProxyDelay("running")
                                        val result =
                                            TcpLatencyProbe.run(
                                                app,
                                                profile.nodes.firstOrNull { it.name == name },
                                                settings.probeTimeout,
                                                run,
                                            )
                                        if (
                                            mutableProxyTests.value.running &&
                                                proxyVersion == version
                                        ) {
                                            pending[key] = result
                                            completed.incrementAndGet()
                                        }
                                    } finally {
                                        tcpRuns.remove(run)
                                        run.close()
                                    }
                                }
                            }
                        }
                        .awaitAll()
                } finally {
                    updates.cancel()
                    flush()
                    mutableProxyTests.update {
                        if (proxyVersion == version)
                            it.copy(
                                running = false,
                                tcpValues = it.tcpValues.filterValues { v -> v.state != "running" },
                            )
                        else it
                    }
                }
            }
    }

    fun testTcpMember(profile: LocalProfile, name: String, group: String = "") {
        val key = delayKey(profile.id, name, group)
        val unavailable =
            mutableUi.value.busy || mutableDiagnosis.value.running || memberTcpJobs.size >= 2
        if (unavailable || !mutableProxyTests.value.memberFinished(profile.id, name, group)) return
        val node = profile.nodes.firstOrNull { it.name == name } ?: return
        val version = proxyVersion
        val job =
            viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val run = RunControl(data.value.client.probeTimeout.toLong())
                tcpRuns.add(run)
                try {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (proxyVersion != version) return@launch
                    mutableProxyTests.update {
                        if (proxyVersion == version)
                            it.copy(tcpValues = it.tcpValues + (key to ProxyDelay("running")))
                        else it
                    }
                    val result = TcpLatencyProbe.run(app, node, data.value.client.probeTimeout, run)
                    mutableProxyTests.update {
                        if (proxyVersion == version)
                            it.copy(tcpValues = it.tcpValues + (key to result))
                        else it
                    }
                } finally {
                    tcpRuns.remove(run)
                    run.close()
                    memberTcpJobs.remove(key, kotlin.coroutines.coroutineContext[Job])
                    mutableProxyTests.update { state ->
                        if (proxyVersion == version && state.tcpValues[key]?.state == "running")
                            state.copy(tcpValues = state.tcpValues - key)
                        else state
                    }
                }
            }
        if (memberTcpJobs.putIfAbsent(key, job) == null) job.start() else job.cancel()
    }

    fun cancelProxyTests() {
        proxyGeneration.incrementAndGet()
        val sockets = tcpRuns.snapshot()
        val native = testRuns.snapshot()
        val batch = testJob
        testJob = null
        batch?.cancel()
        memberTcpJobs.entries.snapshot().forEach { (key, job) ->
            memberTcpJobs.remove(key, job)
            job.cancel()
        }
        mutableProxyTests.update {
            it.copy(
                running = false,
                values =
                    it.values.filterValues { v ->
                        v.state !in setOf("running", "queued", "members_running")
                    },
                tcpValues = it.tcpValues.filterValues { v -> v.state != "running" },
            )
        }
        // Closing a socket/native transport may wait for its worker. Never do
        // that on the UI thread, and never cancel a later generation's IDs.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            sockets.forEach { it.cancel() }
            native.forEach { Mobile.cancel(it) }
        }
    }

    private fun currentProxyEnvironment(): ProxyEnvironment {
        val state = vpn.value
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java)
        val network = PingVpnService.instance?.boundNetwork ?: manager.activeNetwork
        return ProxyEnvironment(state.stage, state.sessionStartedElapsed, network?.networkHandle)
    }

    private fun invalidateProxyEnvironment() {
        // Network/service notifications can arrive after a new user-triggered
        // batch. Cancel only measurements captured under a different route.
        // Finished results stay: tests dial the nodes themselves, with or without the VPN.
        if (proxyEnvironment == currentProxyEnvironment()) return
        cancelProxyTests()
    }

    fun prepareVpnTransition() {
        cancelProxyTests()
        serviceChecks.cancel()
        cancelDiagnosis()
    }

    fun diagnose() = diagnoseSnapshot(data.value)

    fun diagnoseTarget(profileId: String, name: String, groupName: String = "") {
        val snapshot = data.value
        val profile =
            snapshot.activeProfile?.takeIf {
                it.id == profileId &&
                    mutableProxyTests.value.memberFinished(profileId, name, groupName)
            } ?: return
        val id =
            profile.nodes.firstOrNull { it.name == name }?.id
                ?: profile.groups
                    .firstOrNull { it.name == name && it.supported }
                    ?.let { profile.groupId(it.name) }
                ?: return
        diagnoseSnapshot(snapshot.copy(selectedId = id), allowDuringTests = true)
    }

    fun repeatDiagnosis() =
        diagnoseSnapshot(
            data.value.copy(selectedId = mutableDiagnosis.value.nodeId ?: data.value.selectedId),
            allowDuringTests = true,
        )

    private fun diagnoseSnapshot(snapshot: AppData, allowDuringTests: Boolean = false) {
        val node = snapshot.selected ?: return message(tr(R.string.import_and_choose_a_proxy_first))
        if (!app.nativeReady) return message(tr(R.string.the_native_core_could_not_load_reinstall))
        val busy = mutableDiagnosis.value.running || mutableUi.value.busy
        val batchBlocked = !allowDuringTests && mutableProxyTests.value.running
        if (busy || batchBlocked) return
        val runID = UUID.randomUUID().toString()
        if (!Mobile.prepareProbe(runID, 30_000))
            return message(tr(R.string.the_core_is_busy_try_again_shortly))
        val run = RunControl(30_000)
        control = run
        nativeRun = runID
        mutableDiagnosis.value = DiagnosticUiState(running = true, nodeId = node.id)
        val target = snapshot.probeUrl
        val runtimeConfig = requireNotNull(snapshot.selectedRuntimeConfig)
        val parametersVersion = requestKey(data.value)
        viewModelScope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            var report: Models.Report? = null
            try {
                val parameters = JSONObject(node.config)
                val standardTls =
                    node.type in listOf("anytls", "trojan") || parameters.optBoolean("tls")
                val udpTransport = node.type in listOf("hysteria", "hysteria2", "tuic")
                val beforeNetwork = AndroidCorePlatform(app).underlying()
                val auxiliary =
                    if (node.type == "group")
                        AndroidNetworkBinding.open(app).use { binding ->
                            run.check()
                            Models.Report(
                                runID,
                                java.time.Instant.now().toString(),
                                binding.networkType(),
                                listOf(
                                    Models.Step(
                                        Models.Stage.NETWORK,
                                        if (binding.isCurrent()) Models.State.PASSED
                                        else Models.State.STALE,
                                        if (binding.isCurrent()) Models.Code.NETWORK_READY
                                        else Models.Code.NETWORK_CHANGED,
                                        0,
                                        "",
                                        0,
                                    ),
                                    Models.Step(
                                        Models.Stage.ROUTE,
                                        Models.State.SKIPPED,
                                        Models.Code.GROUP_ROUTE,
                                        0,
                                        "",
                                        0,
                                    ),
                                ),
                                0,
                            )
                        }
                    else
                        DiagnosticEngine().run(
                            Models.Request(
                                parameters.getString("server"),
                                parameters.getInt("port"),
                                standardTls,
                                parameters.optString("sni").ifBlank {
                                    parameters.optString("servername")
                                },
                                Models.Family.AUTO,
                                udpTransport,
                            ),
                            { AndroidNetworkBinding.open(app) },
                            run,
                        ) { steps ->
                            mutableDiagnosis.update {
                                it.copy(
                                    steps =
                                        steps
                                            .filter { step -> step.stage != Models.Stage.PROXY }
                                            .map { step ->
                                                if (
                                                    udpTransport &&
                                                        step.code == Models.Code.DNS_ONLY
                                                )
                                                    Models.Step(
                                                        step.stage,
                                                        step.state,
                                                        Models.Code.UDP_PROTOCOL,
                                                        step.elapsedMs,
                                                        step.addressFamily,
                                                        step.attempt,
                                                    )
                                                else step
                                            }
                                )
                            }
                        }
                val steps =
                    auxiliary.steps
                        .filter { it.stage != Models.Stage.PROXY }
                        .map { step ->
                            if (udpTransport && step.code == Models.Code.DNS_ONLY)
                                Models.Step(
                                    step.stage,
                                    step.state,
                                    Models.Code.UDP_PROTOCOL,
                                    step.elapsedMs,
                                    step.addressFamily,
                                    step.attempt,
                                )
                            else step
                        }
                        .toMutableList()
                if (node.certificateVerificationSkipped)
                    steps.add(
                        0,
                        Models.Step(
                            Models.Stage.POLICY,
                            Models.State.SKIPPED,
                            Models.Code.NODE_CERTIFICATE_UNVERIFIED,
                            0,
                            "",
                            0,
                        ),
                    )
                if (parameters.has("dialer-chain"))
                    steps.add(
                        0,
                        Models.Step(
                            Models.Stage.ROUTE,
                            Models.State.SKIPPED,
                            Models.Code.PROXY_CHAIN_ACTIVE,
                            0,
                            "",
                            0,
                        ),
                    )
                val blocking =
                    steps.firstOrNull {
                        it.state in
                            listOf(Models.State.CANCELLED, Models.State.STALE, Models.State.BLOCKED)
                    }
                val proxy =
                    if (blocking != null)
                        Models.Step(
                            Models.Stage.PROXY,
                            Models.State.SKIPPED,
                            Models.Code.DEPENDENCY_FAILED,
                            0,
                            "",
                            0,
                        )
                    else {
                        run.check()
                        val remaining =
                            30_000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        if (remaining < 100)
                            Models.Step(
                                Models.Stage.PROXY,
                                Models.State.FAILED,
                                Models.Code.DEADLINE,
                                0,
                                "",
                                0,
                            )
                        else {
                            val result =
                                JSONObject(
                                    Mobile.probe(
                                        runtimeConfig,
                                        target,
                                        runID,
                                        minOf(15_000, remaining),
                                    )
                                )
                            if (beforeNetwork != AndroidCorePlatform(app).underlying())
                                Models.Step(
                                    Models.Stage.PROXY,
                                    Models.State.STALE,
                                    Models.Code.NETWORK_CHANGED,
                                    result.optLong("elapsedMs"),
                                    "",
                                    0,
                                )
                            else proxyStep(result)
                        }
                    }
                steps.add(proxy)
                report =
                    Models.Report(
                        runID,
                        auxiliary.timestamp,
                        auxiliary.networkType,
                        steps,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    )
            } catch (_: Exception) {
                val stop = runCatching { run.check() }.exceptionOrNull() as? ProbeFailure
                val steps =
                    mutableDiagnosis.value.steps +
                        Models.Step(
                            Models.Stage.PROXY,
                            stop?.state ?: Models.State.FAILED,
                            stop?.code ?: Models.Code.INTERNAL_ERROR,
                            0,
                            "",
                            0,
                        )
                report =
                    Models.Report(
                        runID,
                        java.time.Instant.now().toString(),
                        tr(R.string.unknown),
                        steps,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    )
            } finally {
                val targetChanged =
                    data.value.copy(selectedId = node.id).selectedRuntimeConfig != runtimeConfig
                if (
                    report != null && (requestKey(data.value) != parametersVersion || targetChanged)
                ) {
                    val outdated = report
                    report =
                        Models.Report(
                            outdated.runId,
                            outdated.timestamp,
                            outdated.networkType,
                            outdated.steps +
                                Models.Step(
                                    Models.Stage.PROXY,
                                    Models.State.STALE,
                                    Models.Code.CONFIG_CHANGED,
                                    0,
                                    "",
                                    0,
                                ),
                            outdated.elapsedMs,
                        )
                }
                run.close()
                Mobile.discardProbe(runID)
                control = null
                nativeRun = null
                mutableDiagnosis.value =
                    DiagnosticUiState(
                        steps = report?.steps.orEmpty(),
                        report = report,
                        nodeId = node.id,
                    )
            }
        }
    }

    private fun proxyStep(result: JSONObject): Models.Step {
        val code =
            when (result.optString("code")) {
                "proxy_request_verified" -> Models.Code.PROXY_OK
                "target_http_status" -> Models.Code.PROXY_HTTP_STATUS
                "timeout_stage_unknown" -> Models.Code.PROXY_TIMEOUT
                "proxy_certificate_failed" -> Models.Code.PROXY_CERTIFICATE
                "target_certificate_failed" -> Models.Code.TARGET_CERTIFICATE
                "proxy_quic_handshake_timeout" -> Models.Code.PROXY_QUIC_TIMEOUT
                "proxy_quic_tls_failed" -> Models.Code.PROXY_QUIC_TLS
                "proxy_dns_failed" -> Models.Code.PROXY_DNS
                "proxy_channel_timeout" -> Models.Code.PROXY_CHANNEL_TIMEOUT
                "proxy_channel_failed" -> Models.Code.PROXY_CHANNEL_FAILED
                "cancelled" -> Models.Code.CANCELLED
                "config_invalid",
                "target_invalid",
                "timeout_invalid" -> Models.Code.PROXY_CONFIG_INVALID
                "already_running",
                "resource_busy" -> Models.Code.CORE_BUSY
                "internal_error" -> Models.Code.INTERNAL_ERROR
                "local_network_restriction" -> Models.Code.LOCAL_RESTRICTION
                else -> Models.Code.PROXY_FAILED
            }
        val state =
            when {
                code == Models.Code.PROXY_OK -> Models.State.PASSED
                code == Models.Code.CANCELLED -> Models.State.CANCELLED
                code == Models.Code.CORE_BUSY ||
                    code == Models.Code.INTERNAL_ERROR ||
                    code == Models.Code.LOCAL_RESTRICTION -> Models.State.BLOCKED
                else -> Models.State.FAILED
            }
        return Models.Step(
            Models.Stage.PROXY,
            state,
            code,
            result.optLong("elapsedMs"),
            "",
            0,
            result.optInt("httpStatus"),
        )
    }

    fun cancelDiagnosis() {
        val current = control
        val id = nativeRun
        viewModelScope.launch(Dispatchers.IO) {
            current?.cancel()
            if (id != null) Mobile.cancel(id)
        }
    }

    private companion object {
        const val FAST_SCAN_TIMEOUT = 2500
        const val MAX_BATCH_DELAY_WORKERS = 15
    }

    override fun onCleared() {
        serviceChecks.close()
        cancelProxyTests()
        cancelDiagnosis()
        super.onCleared()
    }
}

private const val MAX_EXPORTED_PROFILE_BYTES = 32 * 1024 * 1024

// An automatic group may spend half of it picking a member first.
private const val EXIT_TIMEOUT_MS = 12_000
private const val EXIT_FRESH_MS = 60_000L

private data class ProxyEnvironment(val phase: VpnPhase, val session: Long, val network: Long?)
