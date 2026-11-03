// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.pingclash.mobile.Mobile
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_NODE_SECURITY_DEPTH = 8
private const val PROFILE_STORAGE_BYTES = 64 * 1024 * 1024

// The core's import limits (core/mobile/provider_capacity.go), sized for large
// airport subscriptions. Stored profiles are read back within the same bounds.
internal const val MAX_PROFILE_NODES = 5000
internal const val MAX_PROFILE_GROUPS = 512
internal const val MAX_PROFILE_RULES = 50000
internal const val MAX_PROFILE_RULE_BYTES = 4 * 1024 * 1024
private const val RECOVERY_FILE = "profiles.enc.recovery"

// Most users are in mainland China. Like FlClash's baseline DNS (doh.pub / AliDNS) and
// NekoBox's direct DNS (223.5.5.5), default to a domestic resolver: it is reached
// directly, so Chinese sites resolve to nearby servers. Used only when the subscription
// brings no DNS of its own; users can still set any server in Settings.
internal const val DEFAULT_DNS_ADDRESS = "223.5.5.5"
private const val PARALLEL_SUBSCRIPTION_UPDATES = 4

data class LocalNode(
    val id: String,
    val name: String,
    val type: String,
    val config: String,
    val requiresProfile: Boolean = false,
) {
    // Lazy: a group target carries the whole definition, and UI reads of
    // AppData.selected must not reparse it on every recomposition.
    val certificateVerificationSkipped: Boolean by lazy {
        runCatching {
                val parameters = JSONObject(config)
                fun skipped(p: JSONObject, depth: Int = 0): Boolean {
                    if (depth > MAX_NODE_SECURITY_DEPTH) return false
                    return p.optBoolean("skip-cert-verify") ||
                        p.optString("type") == "tuic" && p.optBoolean("disable-sni") ||
                        p.keys()
                            .asSequence()
                            .filter { it != "headers" && it != "ws-headers" }
                            .any { key ->
                                p.optJSONObject(key)?.let { skipped(it, depth + 1) } == true
                            }
                }
                val graphNodes =
                    if (type == "group")
                        JSONObject(parameters.getString("definition")).optJSONArray("proxies")
                    else null
                (graphNodes != null &&
                    (0 until graphNodes.length()).any { skipped(graphNodes.getJSONObject(it)) }) ||
                    skipped(parameters) ||
                    parameters.optJSONArray("dialer-chain")?.let { chain ->
                        (0 until chain.length()).any { skipped(JSONObject(chain.getString(it))) }
                    } == true
            }
            .getOrDefault(false)
    }
}

data class LocalGroup(
    val name: String,
    val type: String,
    val proxies: List<String>,
    val selected: String,
    val supported: Boolean,
    val reason: String = "",
    val unavailable: Int = 0,
) {
    val selectionEditable
        get() =
            supported ||
                type == "select" &&
                    reason in
                        setOf(
                            "group_dependency",
                            "group_node_unavailable",
                            "group_missing",
                            "group_cycle",
                            "group_duplicate",
                        )
}

data class LocalProfile(
    val id: String,
    val name: String,
    val source: String?,
    val nodes: List<LocalNode>,
    val unsupported: Int,
    val routingIgnored: Boolean,
    val rejections: Map<String, Int> = emptyMap(),
    val verificationEnabled: Int = 0,
    val fieldIssues: Map<String, Int> = emptyMap(),
    val groups: List<LocalGroup> = emptyList(),
    val groupsUnsupported: Int = 0,
    val definition: String = "",
    val groupsDeclared: Boolean = false,
    val userSelections: Map<String, String> = emptyMap(),
    val rules: List<String> = emptyList(),
    val rulesOmitted: Int = 0,
    val userAgent: String = DEFAULT_SUBSCRIPTION_UA,
    val quota: SubscriptionQuota? = null,
    val remote: RemoteProfile? = null,
    val activationRequired: Boolean = false,
    val providerRuleWarnings: List<ProviderRuleWarning> = emptyList(),
    val providerRulesSkipped: Int = 0,
    /** Epoch seconds of the last successful subscription download; 0 when unknown. */
    val subscriptionUpdated: Long = 0,
) {
    val autoUpdates
        get() = source?.let(::validHttps) == true

    val unverifiedNodes
        get() = nodes.count { it.certificateVerificationSkipped }

    fun groupId(name: String) = "$id/group/$name"

    fun runtimeConfig(target: String) =
        JSONObject()
            .put("type", "profile-target")
            .put("target", target)
            .put("definition", definition)
            .toString()

    fun groupTarget(group: LocalGroup) =
        LocalNode(groupId(group.name), group.name, "group", runtimeConfig(group.name))

    val hasSubscriptionGroups
        get() =
            groupsDeclared ||
                groups.isNotEmpty() ||
                groupsUnsupported > 0 ||
                runCatching {
                        JSONObject(definition).optJSONArray("proxy-groups")?.length()?.let {
                            it > 0
                        } == true
                    }
                    .getOrDefault(false)

    val defaultTargetId: String?
        get() {
            if (!hasSubscriptionGroups) return nodes.firstOrNull()?.id
            val firstName =
                if (definition.isNotBlank())
                    runCatching {
                            JSONObject(definition)
                                .optJSONArray("proxy-groups")
                                ?.optJSONObject(0)
                                ?.optString("name")
                        }
                        .getOrNull()
                else groups.firstOrNull()?.name
            return groups
                .firstOrNull { it.name == firstName && it.supported }
                ?.let { groupId(it.name) }
        }

    fun containsTarget(target: String?) =
        nodes.any { it.id == target } || groups.any { groupId(it.name) == target }

    fun proxyConfig(name: String): String? {
        // Plain nodes already have validated, resolved configs. Avoid parsing a
        // whole definition of thousands of nodes for every latency request.
        nodes
            .firstOrNull { it.name == name }
            ?.let {
                return if (it.requiresProfile) runtimeConfig(name) else it.config
            }
        return groups.firstOrNull { it.name == name && it.supported }?.let { runtimeConfig(name) }
    }
}

data class AppData(
    val profiles: List<LocalProfile> = emptyList(),
    val selectedId: String? = null,
    val dnsAddress: String = DEFAULT_DNS_ADDRESS,
    val probeUrl: String = "https://www.gstatic.com/generate_204",
    val loadFailed: Boolean = false,
    val certificateNoticeAcknowledged: Boolean = false,
    val activeProfileId: String? = null,
    val client: ClientSettings = ClientSettings(),
    val usage: LocalUsage = LocalUsage(),
    /** Not persisted: some stored entries were unreadable and were dropped at load. */
    val loadRepaired: Boolean = false,
) {
    val nodes
        get() = profiles.flatMap { it.nodes }

    val activeProfile
        get() =
            profiles.firstOrNull { !it.activationRequired && it.id == activeProfileId }
                ?: profiles.firstOrNull { !it.activationRequired && it.containsTarget(selectedId) }
                ?: profiles.firstOrNull { !it.activationRequired }

    // Cached per immutable snapshot: a group target re-encodes the whole
    // definition, and the dashboard reads this on every stats recomposition.
    val selected: LocalNode? by lazy {
        profiles
            .filterNot { it.activationRequired }
            .flatMap { it.nodes }
            .firstOrNull { it.id == selectedId }
            ?: profiles
                .filterNot { it.activationRequired }
                .firstNotNullOfOrNull { p ->
                    p.groups
                        .firstOrNull { it.supported && p.groupId(it.name) == selectedId }
                        ?.let(p::groupTarget)
                }
    }

    val selectedRuntimeConfig: String? by lazy {
        selected?.let { node ->
            if (node.requiresProfile)
                profiles.single { p -> p.nodes.any { it.id == node.id } }.runtimeConfig(node.name)
            else node.config
        }
    }
}

class ProfileRepository(private val context: Context) {
    private companion object {
        val storageLock = Any()
    }

    private val storage = AtomicFile(File(context.filesDir, "profiles.enc"))
    private val mutableData = MutableStateFlow(AppData())
    private val refreshingIds = MutableStateFlow<Set<String>>(emptySet())
    /** Profiles whose subscription or providers are downloading right now. */
    val refreshing = refreshingIds.asStateFlow()

    internal fun <T> trackRefresh(id: String, block: () -> T): T {
        refreshingIds.update { it + id }
        try {
            return block()
        } finally {
            refreshingIds.update { it - id }
        }
    }

    val data = mutableData.asStateFlow()
    private val keyAlias = "pingclash.profiles.v1"
    private val associated = "PingClash profiles v1".toByteArray(Charsets.UTF_8)

    init {
        synchronized(storageLock) {
            if (storage.baseFile.exists() || File(storage.baseFile.path + ".bak").exists()) {
                mutableData.value =
                    runCatching { decode(readEncrypted()) }.getOrElse { AppData(loadFailed = true) }
                if (mutableData.value.loadRepaired)
                    runCatching {
                        storage.baseFile.copyTo(File(context.filesDir, RECOVERY_FILE), true)
                    }
            }
        }
    }

    private fun awaitRuleData() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            RuleData.prepare(context).get()
    }

    @Synchronized
    fun importProfile(
        name: String,
        text: String,
        source: String? = null,
        replaceId: String? = null,
        explicitSelections: Map<String, String>? = null,
        userAgent: String? = null,
        subscriptionResponse: SubscriptionResponse? = null,
        remote: RemoteProfile? = null,
        providerRefresh: Boolean = false,
        startingVpn: Boolean = false,
        requireActivation: Boolean = false,
    ): LocalProfile {
        awaitRuleData()
        when {
            explicitSelections != null && replaceId != null -> ensureSelectable()
            providerRefresh ->
                check(
                    !mutableData.value.loadFailed &&
                        (!VpnRuntime.state.value.active ||
                            startingVpn &&
                                VpnRuntime.state.value.stage in
                                    setOf(VpnPhase.CONNECTING, VpnPhase.ESTABLISHED))
                )
            replaceId == null -> ensureSelectable()
            else -> ensureMutable()
        }
        require(name.isNotBlank() && name.length <= 100) {
            tr(R.string.enter_a_profile_name_of_1_100)
        }
        val previousProfile = mutableData.value.profiles.firstOrNull { it.id == replaceId }
        val (parsed, keptSelections) =
            parseWithSelections(text, previousProfile, explicitSelections)
        if (parsed.optString("state") != "passed")
            throw ProfileImportFailure(importFailureMessage(parsed))
        val nodes = importedNodes(parsed, previousProfile)
        val profile =
            LocalProfile(
                replaceId ?: UUID.randomUUID().toString(),
                name.trim(),
                source,
                nodes,
                parsed.optInt("unsupported"),
                parsed.optBoolean("routingIgnored"),
                rejectionCounts(parsed.optJSONObject("rejections")),
                parsed.optInt("verificationEnabled").coerceIn(0, MAX_PROFILE_NODES),
                importFieldCounts(parsed.optJSONObject("fieldIssues")),
                readGroups(parsed.optJSONArray("groups")),
                parsed.optInt("groupsUnsupported").coerceIn(0, MAX_PROFILE_NODES),
                parsed.optString("definition"),
                parsed.optBoolean("groupsDeclared"),
                keptSelections,
            )
        val current = mutableData.value
        val existing = current.profiles.firstOrNull { it.id == replaceId }
        require(existing != null || current.profiles.size < 20) {
            tr(R.string.a_maximum_of_20_profiles_can_be)
        }
        val updated =
            if (existing != null) current.profiles.map { if (it.id == replaceId) profile else it }
            else current.profiles + profile
        // Keep a refresh selection only when the complete node parameters still match.
        val previous = current.selected
        val activationRequired =
            requireActivation ||
                previousProfile?.activationRequired == true ||
                existing == null && VpnRuntime.state.value.active
        val selected =
            when {
                activationRequired -> current.selectedId
                existing == null ->
                    if (current.activeProfile == null) profile.defaultTargetId
                    else current.selectedId
                existing.id == current.activeProfile?.id &&
                    (current.selectedId == null ||
                        !existing.hasSubscriptionGroups && profile.hasSubscriptionGroups) ->
                    profile.defaultTargetId
                existing.groups.any { existing.groupId(it.name) == current.selectedId } ->
                    profile.groups
                        .firstOrNull { it.supported && it.name == previous?.name }
                        ?.let { profile.groupId(it.name) }
                existing.nodes.none { it.id == current.selectedId } -> current.selectedId
                else ->
                    nodes
                        .firstOrNull {
                            previous != null &&
                                it.name == previous.name &&
                                it.config == previous.config
                        }
                        ?.id
                        ?: nodes
                            .filter {
                                previous != null &&
                                    it.name == previous.name &&
                                    it.type == previous.type
                            }
                            .singleOrNull()
                            ?.id
            }
        val withRules =
            profile
                .copy(
                    remote = if (explicitSelections != null) previousProfile?.remote else remote,
                    userAgent = userAgent ?: previousProfile?.userAgent ?: DEFAULT_SUBSCRIPTION_UA,
                    quota =
                        if (subscriptionResponse != null) subscriptionResponse.quota
                        else previousProfile?.quota,
                    subscriptionUpdated =
                        if (subscriptionResponse != null || previousProfile == null)
                            java.time.Instant.now().epochSecond
                        else previousProfile.subscriptionUpdated,
                )
                .withRuleDetails(parsed, previousProfile, explicitSelections != null)

        val imported = withRules.copy(activationRequired = activationRequired)
        commit(
            current.copy(
                profiles = updated.map { if (it.id == profile.id) imported else it },
                selectedId = selected,
                activeProfileId =
                    current.activeProfile?.id ?: profile.id.takeUnless { activationRequired },
            )
        )
        return imported
    }

    private fun LocalProfile.withRuleDetails(
        parsed: JSONObject,
        previous: LocalProfile?,
        retainRules: Boolean,
    ) =
        copy(
            providerRuleWarnings =
                readProviderRuleWarnings(parsed.optJSONArray("providerRuleWarnings")),
            providerRulesSkipped = providerSkippedCount(parsed),
            rules =
                if (retainRules) previous?.rules.orEmpty()
                else readRules(parsed.optJSONArray("rules")),
            rulesOmitted =
                if (retainRules) previous?.rulesOmitted ?: 0
                else parsed.optInt("rulesOmitted").coerceAtLeast(0),
        )

    @Suppress(
        "LongParameterList"
    ) // Existing import API carries private source and response metadata.
    fun importWithProviders(
        name: String,
        text: String,
        source: String? = null,
        replaceId: String? = null,
        userAgent: String = DEFAULT_SUBSCRIPTION_UA,
        response: SubscriptionResponse? = null,
    ): LocalProfile {
        // A new import may defer unreachable rule providers; a refresh stays strict.
        val prepared = RemoteProfiles.loadForImport(text, userAgent, deferRules = replaceId == null)
        return importProfile(
            name,
            prepared.content,
            source,
            replaceId,
            userAgent = userAgent,
            subscriptionResponse = response,
            remote = prepared.remote,
        )
    }

    fun refreshProviders(profile: LocalProfile, force: Boolean = false) {
        val remote = profile.remote ?: return
        if (
            !force &&
                !RemoteProfiles.due(remote) &&
                (!remote.pending || VpnRuntime.state.value.active)
        )
            return
        trackRefresh(profile.id) { refreshProvidersNow(profile, remote, force) }
    }

    // Unchanged pre-existing body (previously baselined as refreshProviders): one
    // transaction covering download, validation, live/pending commit and error record.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun refreshProvidersNow(profile: LocalProfile, remote: RemoteProfile, force: Boolean) {
        try {
            val prepared = RemoteProfiles.load(remote.original, profile.userAgent, remote, force)
            // Parse the complete graph before replacing even the encrypted cache.
            val checked = JSONObject(Mobile.importProfile(prepared.content))
            if (checked.optString("state") != "passed")
                throw ProviderFailure(0, "proxy", "provider_payload")
            synchronized(this) {
                val current = mutableData.value
                if (
                    current.loadFailed ||
                        current.profiles.firstOrNull { it.id == profile.id } != profile
                )
                    return
                if (VpnRuntime.state.value.active) {
                    commit(
                        current.copy(
                            profiles =
                                current.profiles.map {
                                    if (it.id == profile.id)
                                        it.copy(
                                            remote =
                                                prepared.remote?.copy(
                                                    pending =
                                                        remote.pending ||
                                                            prepared.content !=
                                                                RemoteProfiles.resolve(
                                                                    remote.original,
                                                                    remote.bodies,
                                                                )
                                                )
                                        )
                                    else it
                                }
                        )
                    )
                } else
                    importProfile(
                        profile.name,
                        prepared.content,
                        profile.source,
                        profile.id,
                        userAgent = profile.userAgent,
                        remote = prepared.remote,
                        providerRefresh = true,
                    )
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            return
        } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted) return
            val problem =
                failure as? ProviderFailure ?: ProviderFailure(0, "proxy", "provider_payload")
            synchronized(this) {
                val current = mutableData.value
                if (
                    !current.loadFailed &&
                        current.profiles.firstOrNull { it.id == profile.id } == profile
                )
                    commit(
                        current.copy(
                            profiles =
                                current.profiles.map {
                                    if (it.id == profile.id)
                                        it.copy(
                                            remote =
                                                remote.copy(
                                                    error =
                                                        "${problem.index}:${problem.kind}:${problem.code}",
                                                    retryAfter =
                                                        java.time.Instant.now().epochSecond + 60,
                                                )
                                        )
                                    else it
                                }
                        )
                    )
            }
            if (force) throw problem
        }
    }

    /**
     * Applies downloaded provider bodies to a running VPN through the hot routing update. Returns
     * true when the service was asked to reconfigure.
     */
    @Synchronized
    internal fun applyPendingProvidersLive(): Boolean {
        if (
            VpnRuntime.state.value.stage != VpnPhase.ESTABLISHED ||
                mutableData.value.activeProfile?.remote?.pending != true
        )
            return false
        applyPendingProviders()
        reconfigureLiveVpn()
        return true
    }

    /**
     * Background subscription update. Skips a profile the user changed meanwhile; while the VPN is
     * established the new profile is applied through the hot routing update.
     */
    /** Returns true when the new profile was imported into an established VPN. */
    fun refreshSubscription(
        profile: LocalProfile,
        forceProviders: Boolean = false,
        applyLive: Boolean = true,
    ): Boolean {
        val source = profile.source?.takeIf { profile.autoUpdates } ?: return false
        val live =
            trackRefresh(profile.id) { refreshSubscriptionNow(profile, source, forceProviders) }
        if (live && applyLive) reconfigureLiveVpn()
        return live
    }

    private fun refreshSubscriptionNow(
        profile: LocalProfile,
        source: String,
        forceProviders: Boolean,
    ): Boolean {
        val response = SubscriptionDownloader.fetchResponse(source, profile.userAgent)
        val prepared =
            RemoteProfiles.load(response.content, profile.userAgent, profile.remote, forceProviders)
        synchronized(this) {
            val current = mutableData.value
            val unchanged =
                !current.loadFailed &&
                    current.profiles.firstOrNull { it.id == profile.id } == profile
            val live = VpnRuntime.state.value.stage == VpnPhase.ESTABLISHED
            if (!unchanged || VpnRuntime.state.value.active && !live) return false
            importProfile(
                profile.name,
                prepared.content,
                source,
                profile.id,
                userAgent = profile.userAgent,
                subscriptionResponse = response,
                remote = prepared.remote,
                providerRefresh = true,
                startingVpn = live,
            )
            return live
        }
    }

    /**
     * "Update all": every subscription at once (a few downloads in parallel), also while connected.
     * A running VPN is reconfigured once at the end. Returns the number updated and failed.
     */
    fun refreshAllSubscriptions(): Pair<Int, Int> {
        val profiles = mutableData.value.profiles.filter { it.autoUpdates }
        if (profiles.isEmpty()) return 0 to 0
        val pool =
            java.util.concurrent.Executors.newFixedThreadPool(
                minOf(PARALLEL_SUBSCRIPTION_UPDATES, profiles.size)
            )
        val results =
            try {
                profiles
                    .map { profile ->
                        pool.submit<Boolean> {
                            refreshSubscription(profile, forceProviders = true, applyLive = false)
                        }
                    }
                    .map { future ->
                        try {
                            Result.success(future.get())
                        } catch (failure: java.util.concurrent.ExecutionException) {
                            Result.failure(failure.cause ?: failure)
                        }
                    }
            } finally {
                pool.shutdownNow()
            }
        if (results.any { it.getOrNull() == true }) reconfigureLiveVpn()
        val failed = results.count { it.isFailure }
        return results.size - failed to failed
    }

    /** Reorders the profile list; display order only, routing is unaffected. */
    @Synchronized
    fun reorderProfiles(ids: List<String>) {
        check(!mutableData.value.loadFailed) { tr(R.string.local_profiles_could_not_be_read_the) }
        val byId = mutableData.value.profiles.associateBy { it.id }
        // Ignore a stale order from a list that changed during the drag.
        if (ids.size != byId.size || ids.toSet() != byId.keys) return
        commit(mutableData.value.copy(profiles = ids.map(byId::getValue)))
    }

    private fun reconfigureLiveVpn() {
        context.startService(
            android.content
                .Intent(context, PingVpnService::class.java)
                .setAction(PingVpnService.RECONFIGURE)
        )
    }

    /** Last resort when stored data cannot be read: start over with empty local data. */
    @Synchronized
    fun resetLocalData() {
        check(!VpnRuntime.state.value.active) {
            tr(R.string.disconnect_the_vpn_before_changing_profile)
        }
        synchronized(storageLock) {
            storage.delete()
            // A lost or invalidated key cannot decrypt anything; the next save makes a new one.
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            }
            mutableData.value = AppData()
        }
    }

    @Synchronized
    internal fun applyPendingProviders() {
        val profile = mutableData.value.activeProfile ?: return
        val remote = profile.remote?.takeIf { it.pending } ?: return
        val content = RemoteProfiles.resolve(remote.original, remote.bodies)
        importProfile(
            profile.name,
            content,
            profile.source,
            profile.id,
            userAgent = profile.userAgent,
            remote = remote.copy(pending = false),
            providerRefresh = true,
            startingVpn = true,
        )
    }

    @Synchronized
    fun select(id: String) {
        ensureSelectable()
        val owner = mutableData.value.profiles.single { it.containsTarget(id) }
        val next =
            mutableData.value.copy(
                profiles =
                    mutableData.value.profiles.map {
                        if (it.id == owner.id) it.copy(activationRequired = false) else it
                    },
                selectedId = id,
                activeProfileId = owner.id,
            )
        require(next.selected != null)
        commit(next)
    }

    @Synchronized
    fun activate(profileId: String) {
        ensureSelectable()
        val profile = mutableData.value.profiles.single { it.id == profileId }
        commit(
            mutableData.value.copy(
                profiles =
                    mutableData.value.profiles.map {
                        if (it.id == profile.id) it.copy(activationRequired = false) else it
                    },
                selectedId = profile.defaultTargetId,
                activeProfileId = profile.id,
            )
        )
    }

    @Synchronized
    fun selectGroup(profileId: String, groupName: String, choice: String): LocalProfile {
        ensureSelectable()
        val profile = mutableData.value.profiles.single { it.id == profileId }
        val group = profile.groups.single { it.name == groupName }
        require(
            group.selectionEditable &&
                group.type != "load-balance" &&
                (choice in group.proxies || choice.isEmpty() && group.type != "select")
        )
        val definition = JSONObject(profile.definition)
        val picks = definition.optJSONObject("pingclash-selections") ?: JSONObject()
        picks.put(groupName, choice)
        definition.put("pingclash-selections", picks)
        if (!group.supported) {
            val checked = JSONObject(Mobile.importProfile(definition.toString()))
            if (
                checked.optString("state") != "passed" ||
                    readGroups(checked.optJSONArray("groups")).none {
                        it.name == groupName && it.supported
                    }
            )
                throw ProfileImportFailure(tr(R.string.the_selected_member_cannot_build_this_grou))
        }
        val before = mutableData.value
        val imported =
            importProfile(
                profile.name,
                definition.toString(),
                profile.source,
                profile.id,
                explicitSelections = profile.userSelections + (groupName to choice),
            )
        // A pick that makes the selected group unbuildable would silently clear the
        // selection and fail the next connect; keep the previous choice instead.
        if (before.selected != null && mutableData.value.selected == null) {
            commit(before)
            throw ProfileImportFailure(tr(R.string.the_selected_member_cannot_build_this_grou))
        }
        return imported
    }

    @Synchronized
    fun reparse(profileId: String): LocalProfile {
        ensureMutable()
        val profile = mutableData.value.profiles.single { it.id == profileId }
        require(profile.definition.isNotBlank()) {
            tr(R.string.this_profile_has_no_original_definition_to)
        }
        return importProfile(
            profile.name,
            profile.definition,
            profile.source,
            profile.id,
            explicitSelections = profile.userSelections,
        )
    }

    @Synchronized
    fun delete(id: String) {
        ensureMutable()
        val profiles = mutableData.value.profiles.filterNot { it.id == id }
        val current = mutableData.value
        val active =
            profiles.firstOrNull { !it.activationRequired && it.id == current.activeProfile?.id }
                ?: profiles.firstOrNull { !it.activationRequired }
        val selected =
            if (active?.id == current.activeProfile?.id) current.selectedId
            else active?.defaultTargetId
        commit(
            current.copy(profiles = profiles, selectedId = selected, activeProfileId = active?.id)
        )
    }

    @Synchronized
    fun settings(dns: String, probeUrl: String) {
        ensureMutable()
        require(dns.isNotBlank() && dns.length <= 80) {
            tr(R.string.enter_a_dns_ip_address_with_an)
        }
        // Native validation also verifies the literal IP and port before establishing TUN.
        require(validDns(dns)) { tr(R.string.dns_requires_a_valid_ip_address_with) }
        require(probeUrl.length <= 2048 && validHttps(probeUrl)) {
            tr(R.string.the_test_target_must_be_an_https)
        }
        commit(mutableData.value.copy(dnsAddress = dns, probeUrl = probeUrl))
    }

    @Synchronized
    fun clientSettings(value: ClientSettings, dns: String, probe: String) {
        ensureMutable()
        validateClientSettings(value)
        require(dns.length <= 80 && validDns(dns)) {
            tr(R.string.dns_requires_a_valid_ip_address_with)
        }
        require(probe.length <= 2048 && validHttps(probe)) {
            tr(R.string.the_test_target_must_be_an_https)
        }
        val current = mutableData.value
        validateRuntimeSettings(current, value)
        commit(
            current.copy(
                client = value,
                dnsAddress = dns,
                probeUrl = probe,
                usage = if (value.keepUsage) current.usage else LocalUsage(),
            )
        )
        if (value.language != current.client.language) UiStrings.apply(value.language)
    }

    @Synchronized
    internal fun patchSettings(before: ClientSettings, after: ClientSettings) {
        val current = mutableData.value
        val value = mergeSettingsDelta(before, after, current.client, deferredSettingFields)
        validateClientSettings(value)
        if (VpnRuntime.state.value.active) {
            check(
                !current.loadFailed &&
                    changedSettingFields(current.client, value).all { it in liveAppearanceFields }
            )
            commit(
                current.copy(
                    client = value,
                    usage = if (value.keepUsage) current.usage else LocalUsage(),
                )
            )
        } else clientSettings(value, current.dnsAddress, current.probeUrl)
    }

    /** The sole live routing preference; all other settings keep ensureMutable's VPN lock. */
    @Synchronized
    fun routeMode(mode: String) {
        val current = mutableData.value
        check(!current.loadFailed) { tr(R.string.local_profiles_could_not_be_read_the) }
        check(VpnRuntime.state.value.phase !in listOf("connecting", "stopping")) {
            tr(R.string.disconnect_the_vpn_before_changing_profile)
        }
        val value = current.client.copy(mode = mode)
        validateClientSettings(value)
        validateRuntimeSettings(current, value)
        commit(current.copy(client = value))
    }

    @Synchronized
    fun appearance(
        language: String,
        theme: String,
        dynamicColor: Boolean? = null,
        reduceEffects: Boolean? = null,
        scenery: ScenePreferenceChange? = null,
    ) {
        val current = mutableData.value
        check(!current.loadFailed)
        val value =
            current.client.copy(
                language = language,
                theme = theme,
                dynamicColor = dynamicColor ?: current.client.dynamicColor,
                reduceEffects = reduceEffects ?: current.client.reduceEffects,
                scene = scenery?.scene ?: current.client.scene,
                reduceTransparency =
                    scenery?.reduceTransparency ?: current.client.reduceTransparency,
                solidColor = scenery?.solidColor ?: current.client.solidColor,
                solidSwatches = scenery?.solidSwatches ?: current.client.solidSwatches,
            )
        validateClientSettings(value)
        commit(current.copy(client = value))
        UiStrings.apply(language)
    }

    @Synchronized
    fun resetUsage() {
        ensureMutable()
        commit(mutableData.value.copy(usage = LocalUsage()))
    }

    @Synchronized
    internal fun recordUsage(up: Long, down: Long, seconds: Long, countSession: Boolean = true) {
        val current = mutableData.value
        if (current.loadFailed || !current.client.keepUsage) return
        fun plus(a: Long, b: Long) = a + (b.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - a))
        commit(
            current.copy(
                usage =
                    LocalUsage(
                        plus(current.usage.uploaded, up),
                        plus(current.usage.downloaded, down),
                        plus(current.usage.seconds, seconds),
                        plus(current.usage.sessions, if (countSession) 1 else 0),
                    )
            )
        )
    }

    /** Called only by the serialized VPN service after a rejected hot update. */
    @Synchronized
    internal fun restoreRouting(previous: AppData, attempted: AppData) {
        val current = mutableData.value
        if (current.activeProfileId != attempted.activeProfileId) return
        if (
            current.selectedId != attempted.selectedId ||
                current.profiles != attempted.profiles ||
                current.client.mode != attempted.client.mode
        )
            return
        val previousActive = previous.profiles.firstOrNull { it.id == previous.activeProfileId }
        val profiles =
            current.profiles.map { profile ->
                when {
                    previousActive != null && profile.id == previousActive.id -> previousActive
                    profile.id == attempted.activeProfileId &&
                        attempted.activeProfileId != previous.activeProfileId ->
                        profile.copy(activationRequired = true)
                    else -> profile
                }
            }
        commit(
            current.copy(
                selectedId = previous.selectedId,
                activeProfileId = previous.activeProfileId,
                profiles = profiles,
                client = current.client.copy(mode = previous.client.mode),
            )
        )
    }

    @Synchronized
    fun subscriptionUserAgent(id: String, value: String) {
        ensureMutable()
        if (!validSubscriptionUa(value)) throw SettingsFailure(tr(R.string.subscription_ua_invalid))
        commit(
            mutableData.value.copy(
                profiles =
                    mutableData.value.profiles.map {
                        if (it.id == id) it.copy(userAgent = value) else it
                    }
            )
        )
    }

    @Synchronized
    fun rename(id: String, name: String) {
        ensureMutable()
        require(name.isNotBlank() && name.length <= 100) {
            tr(R.string.enter_a_profile_name_of_1_100)
        }
        commit(
            mutableData.value.copy(
                profiles =
                    mutableData.value.profiles.map {
                        if (it.id == id) it.copy(name = name.trim()) else it
                    }
            )
        )
    }

    @Synchronized
    fun acknowledgeCertificateNotice() {
        val current = mutableData.value
        check(!current.loadFailed) { tr(R.string.local_profiles_could_not_be_read_the) }
        if (!current.certificateNoticeAcknowledged)
            commit(current.copy(certificateNoticeAcknowledged = true))
    }

    private fun ensureSelectable() {
        check(!mutableData.value.loadFailed) { tr(R.string.local_profiles_could_not_be_read_the) }
        check(VpnRuntime.state.value.phase !in listOf("connecting", "stopping")) {
            tr(R.string.disconnect_the_vpn_before_changing_profile)
        }
    }

    private fun ensureMutable() {
        check(!mutableData.value.loadFailed) { tr(R.string.local_profiles_could_not_be_read_the) }
        check(!VpnRuntime.state.value.active) {
            tr(R.string.disconnect_the_vpn_before_changing_profile)
        }
    }

    private fun commit(value: AppData) {
        synchronized(storageLock) {
            val plaintext = encode(value).toByteArray(Charsets.UTF_8)
            require(plaintext.size <= PROFILE_STORAGE_BYTES) {
                tr(R.string.local_profile_storage_limit_exceeded)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret(create = true))
            cipher.updateAAD(associated)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
            val stream = storage.startWrite()
            try {
                stream.write(encrypted)
                storage.finishWrite(stream)
            } catch (error: Exception) {
                storage.failWrite(stream)
                throw error
            }
            mutableData.value = value
        }
    }

    private fun readEncrypted(): String {
        val encrypted =
            storage.openRead().use { stream ->
                val content = stream.readBytesLimited(PROFILE_STORAGE_BYTES + 64)
                require(content.size >= 29 && content[0] == 1.toByte())
                content
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret(create = false),
            GCMParameterSpec(128, encrypted.copyOfRange(1, 13)),
        )
        cipher.updateAAD(associated)
        return cipher.doFinal(encrypted.copyOfRange(13, encrypted.size)).toString(Charsets.UTF_8)
    }

    private fun secret(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let {
            return it
        }
        check(create) { "key_unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    private fun encode(data: AppData): String =
        JSONObject()
            .apply {
                put("version", 1)
                put("selected", data.selectedId)
                put("dns", data.dnsAddress)
                put("probe", data.probeUrl)
                put("certificateNoticeAcknowledged", data.certificateNoticeAcknowledged)
                put("activeProfile", data.activeProfile?.id)
                put("client", data.client.json())
                put("usage", data.usage.json())
                put(
                    "profiles",
                    JSONArray().apply {
                        data.profiles.forEach { profile ->
                            put(
                                JSONObject().apply {
                                    put("id", profile.id)
                                    put("name", profile.name)
                                    put("source", profile.source)
                                    put("activationRequired", profile.activationRequired)
                                    put("unsupported", profile.unsupported)
                                    put("routingIgnored", profile.routingIgnored)
                                    put("rejections", JSONObject(profile.rejections))
                                    put("verificationEnabled", profile.verificationEnabled)
                                    put("fieldIssues", JSONObject(profile.fieldIssues))
                                    put(
                                        "groups",
                                        JSONArray().apply {
                                            profile.groups.forEach { group ->
                                                put(
                                                    JSONObject().apply {
                                                        put("name", group.name)
                                                        put("type", group.type)
                                                        put("proxies", JSONArray(group.proxies))
                                                        put("selected", group.selected)
                                                        put("supported", group.supported)
                                                        put("reason", group.reason)
                                                        put("unavailable", group.unavailable)
                                                    }
                                                )
                                            }
                                        },
                                    )
                                    put("groupsUnsupported", profile.groupsUnsupported)
                                    put("definition", profile.definition)
                                    put("groupsDeclared", profile.groupsDeclared)
                                    put("userSelections", JSONObject(profile.userSelections))
                                    put("rules", JSONArray(profile.rules))
                                    put("rulesOmitted", profile.rulesOmitted)
                                    put(
                                        "providerRuleWarnings",
                                        JSONArray(profile.providerRuleWarnings.map { it.json() }),
                                    )
                                    put("providerRulesSkipped", profile.providerRulesSkipped)
                                    put("userAgent", profile.userAgent)
                                    put("quota", profile.quota?.json())
                                    put("remote", profile.remote?.json())
                                    put("subscriptionUpdated", profile.subscriptionUpdated)
                                    put(
                                        "nodes",
                                        JSONArray().apply {
                                            profile.nodes.forEach { node ->
                                                put(
                                                    JSONObject().apply {
                                                        put("id", node.id)
                                                        put("name", node.name)
                                                        put("type", node.type)
                                                        put("config", node.config)
                                                        put("requiresProfile", node.requiresProfile)
                                                    }
                                                )
                                            }
                                        },
                                    )
                                }
                            )
                        }
                    },
                )
            }
            .toString()

    // Lenient: an unreadable entry is dropped or reset to its default instead of locking every
    // feature. Only an unreadable file, key or format version still sets loadFailed. Stricter
    // validation in a later version must not lock out existing users after an update.
    private fun decode(text: String): AppData {
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        var repaired = false
        fun <T> lenient(fallback: T, read: () -> T): T =
            runCatching(read).getOrElse {
                repaired = true
                fallback
            }
        val profiles = root.getJSONArray("profiles")
        if (profiles.length() > 20) repaired = true
        val decoded =
            (0 until minOf(profiles.length(), 20)).mapNotNull { index ->
                lenient(null) { decodeProfile(profiles.getJSONObject(index), ::lenient) }
            }
        val defaults = AppData()
        return AppData(
            profiles = decoded,
            selectedId = root.optString("selected").takeIf { it.isNotBlank() && it != "null" },
            dnsAddress = lenient(defaults.dnsAddress) { root.getString("dns") },
            probeUrl = lenient(defaults.probeUrl) { root.getString("probe") },
            certificateNoticeAcknowledged = root.optBoolean("certificateNoticeAcknowledged"),
            activeProfileId =
                root.optString("activeProfile").takeIf { it.isNotBlank() && it != "null" },
            client =
                lenient(ClientSettings()) {
                    ClientSettings.read(root.optJSONObject("client")).also(::validateClientSettings)
                },
            usage = lenient(LocalUsage()) { LocalUsage.read(root.optJSONObject("usage")) },
            loadRepaired = repaired,
        )
    }

    private fun decodeProfile(
        profile: JSONObject,
        lenient: (Any?, () -> Any?) -> Any?,
    ): LocalProfile {
        @Suppress("UNCHECKED_CAST")
        fun <T> optional(fallback: T, read: () -> T): T = lenient(fallback, read) as T
        val nodes = profile.getJSONArray("nodes")
        val decodedNodes =
            (0 until minOf(nodes.length(), MAX_PROFILE_NODES)).mapNotNull { n ->
                optional<LocalNode?>(null) {
                    val node = nodes.getJSONObject(n)
                    LocalNode(
                        node.getString("id"),
                        node.getString("name"),
                        node.getString("type"),
                        node.getString("config"),
                        node.optBoolean("requiresProfile"),
                    )
                }
            }
        require(decodedNodes.isNotEmpty())
        return LocalProfile(
            profile.getString("id"),
            profile.getString("name"),
            profile.optString("source").takeIf { it.isNotBlank() && it != "null" },
            decodedNodes,
            profile.optInt("unsupported"),
            profile.optBoolean("routingIgnored"),
            optional(emptyMap()) { rejectionCounts(profile.optJSONObject("rejections")) },
            profile.optInt("verificationEnabled").coerceIn(0, MAX_PROFILE_NODES),
            optional(emptyMap()) { importFieldCounts(profile.optJSONObject("fieldIssues")) },
            optional(emptyList()) { readGroups(profile.optJSONArray("groups")) },
            profile.optInt("groupsUnsupported").coerceIn(0, MAX_PROFILE_NODES),
            profile.optString("definition"),
            profile.optBoolean("groupsDeclared"),
            optional(emptyMap()) {
                profile
                    .optJSONObject("userSelections")
                    ?.let { picks ->
                        require(picks.length() <= MAX_PROFILE_GROUPS)
                        picks.keys().asSequence().associateWith { picks.getString(it) }
                    }
                    .orEmpty()
            },
            optional(emptyList()) { readRules(profile.optJSONArray("rules")) },
            profile.optInt("rulesOmitted").coerceAtLeast(0),
            optional(DEFAULT_SUBSCRIPTION_UA) {
                profile.optString("userAgent", DEFAULT_SUBSCRIPTION_UA).also {
                    require(validSubscriptionUa(it))
                }
            },
            optional(null) { SubscriptionQuota.read(profile.optJSONObject("quota")) },
            optional(null) { RemoteProfile.read(profile.optJSONObject("remote")) },
            profile.optBoolean("activationRequired"),
            optional(emptyList()) {
                readProviderRuleWarnings(profile.optJSONArray("providerRuleWarnings"))
            },
            optional(0) { providerSkippedCount(profile) },
            profile.optLong("subscriptionUpdated").coerceAtLeast(0),
        )
    }
}

internal class SettingsFailure(
    val publicMessage: String,
    val stage: IssueStage = IssueStage.OPERATION,
) : IllegalArgumentException("settings_invalid")

private fun readRules(value: JSONArray?): List<String> {
    if (value == null) return emptyList()
    require(value.length() <= MAX_PROFILE_RULES)
    val rules = (0 until value.length()).map(value::getString)
    require(rules.sumOf { it.length } <= MAX_PROFILE_RULE_BYTES && rules.all { it.length <= 2048 })
    return rules
}

internal fun readGroups(value: JSONArray?): List<LocalGroup> =
    if (value == null) emptyList()
    else {
        require(value.length() <= MAX_PROFILE_GROUPS)
        (0 until value.length()).map { index ->
            val group = value.getJSONObject(index)
            val members = group.getJSONArray("proxies")
            require(members.length() in 1..MAX_PROFILE_NODES)
            LocalGroup(
                group.getString("name"),
                group.getString("type"),
                (0 until members.length()).map(members::getString),
                group.getString("selected"),
                group.getBoolean("supported"),
                group.optString("reason"),
                group.optInt("unavailable").coerceIn(0, MAX_PROFILE_NODES),
            )
        }
    }

internal fun validHttps(raw: String): Boolean =
    runCatching {
            val uri = URI(raw)
            raw.length <= 4096 &&
                uri.scheme == "https" &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                httpsAuthority(uri) != null
        }
        .getOrDefault(false)

private fun validDns(raw: String): Boolean =
    runCatching {
            val address =
                if (android.net.InetAddresses.isNumericAddress(raw)) raw
                else {
                    val uri = URI("dns://$raw")
                    require(
                        uri.port in 1..65535 &&
                            uri.rawUserInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null &&
                            uri.rawPath.isNullOrEmpty()
                    )
                    requireNotNull(uri.host).removePrefix("[").removeSuffix("]")
                }
            val ip = android.net.InetAddresses.parseNumericAddress(address)
            !ip.isAnyLocalAddress && !ip.isMulticastAddress
        }
        .getOrDefault(false)

internal fun java.io.InputStream.readBytesLimited(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { tr(R.string.content_exceeds_the_size_limit) }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal object SubscriptionDownloader {
    private val timer =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "subscription-deadline").apply { isDaemon = true }
        }

    fun fetch(source: String, userAgent: String = DEFAULT_SUBSCRIPTION_UA): String =
        fetchResponse(source, userAgent).content

    fun fetchResponse(
        source: String,
        userAgent: String = DEFAULT_SUBSCRIPTION_UA,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 30_000,
        bodyLimit: SubscriptionReadLimit = SubscriptionReadLimit(),
    ): SubscriptionResponse {
        validateSubscriptionFetch(source, userAgent)
        var address = URI(source)
        var requestHeaders = headers
        val deadline =
            System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceIn(1, 30_000).toLong())
        repeat(4) {
            checkSubscriptionDeadline(deadline)
            val connection = address.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", userAgent)
            requestHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val expired = java.util.concurrent.atomic.AtomicBoolean(false)
            val expiry =
                timer.schedule(
                    {
                        expired.set(true)
                        connection.disconnect()
                    },
                    maxOf(1, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS,
                )
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val previous = address
                    address = subscriptionRedirect(address, connection.getHeaderField("Location"))
                    requestHeaders =
                        redirectedSubscriptionHeaders(requestHeaders, previous, address)
                } else {
                    requireSubscriptionSuccess(status)
                    val content = subscriptionBody(connection, bodyLimit)
                    return SubscriptionResponse(
                        content,
                        SubscriptionQuota.parse(connection.getHeaderField("subscription-userinfo")),
                        SubscriptionNames.fromHeaders(
                            connection.getHeaderField("profile-title"),
                            connection.getHeaderField("Content-Disposition"),
                        ),
                    )
                }
            } catch (failure: Exception) {
                checkSubscriptionDeadline(deadline, expired.get())
                throw failure
            } finally {
                expiry.cancel(false)
                connection.disconnect()
            }
        }
        throw SubscriptionTransferFailure("provider_redirect")
    }
}

internal class SubscriptionHttpFailure(val status: Int) :
    java.io.IOException("subscription_http_status")

internal class ProfileImportFailure(val publicMessage: String) :
    IllegalArgumentException("profile_import_failed")

internal val rejectionLabels
    get() =
        linkedMapOf(
            "resource_limit" to tr(R.string.configuration_exceeds_local_limits),
            "security_policy" to tr(R.string.other_security_restrictions_legacy_record),
            "tls_policy" to tr(R.string.unsupported_tls_options),
            "proxy_chain" to tr(R.string.unresolved_dialer_dependency_missing_proxy),
            "replay_policy" to tr(R.string.unsupported_0_rtt_option_enabled),
            "plaintext_transport" to tr(R.string.remote_transport_lacks_encryption),
            "cipher_policy" to tr(R.string.unsupported_cipher_or_authentication_mode),
            "udp_policy" to tr(R.string.unsupported_remote_socks_udp),
            "unsupported_protocol" to tr(R.string.unsupported_protocol),
            "invalid_parameters" to tr(R.string.parameters_or_their_combination_were_rejec),
            "unsupported_fields" to tr(R.string.unsupported_fields_or_invalid_value_format),
            "invalid_node" to tr(R.string.incomplete_proxy_configuration),
        )

internal fun rejectionCounts(value: JSONObject?): Map<String, Int> =
    rejectionLabels.keys
        .mapNotNull { key ->
            value?.optInt(key, 0)?.takeIf { it in 1..MAX_PROFILE_NODES }?.let { key to it }
        }
        .toMap()

internal fun rejectionSummary(counts: Map<String, Int>): String =
    rejectionLabels
        .mapNotNull { (key, label) ->
            counts[key]?.let { trPlural(R.plurals.count_proxy_proxies_2, (it).toInt(), label, it) }
        }
        .joinToString("；")

internal fun importFailureMessage(parsed: JSONObject): String =
    when (parsed.optString("code")) {
        "subscription_html" -> tr(R.string.the_link_returned_a_web_page_rather)
        "singbox_format" -> tr(R.string.sing_box_json_is_not_supported_use)
        "provider_only" -> tr(R.string.the_configuration_contains_remote_provider)
        "no_supported_nodes" -> {
            val counts = rejectionCounts(parsed.optJSONObject("rejections"))
            val detail =
                rejectionSummary(counts) +
                    importFieldSummary(importFieldCounts(parsed.optJSONObject("fieldIssues")))
                        .takeIf { it.isNotBlank() }
                        ?.let { "。$it" }
                        .orEmpty()
            tr(R.string.no_proxies_could_be_imported) +
                detail.takeIf { it.isNotBlank() }?.let { "$it。" }.orEmpty() +
                tr(R.string.no_usable_nodes_found_check_the_subscripti)
        }
        else -> tr(R.string.configuration_is_invalid_unsupported_or_ex)
    }

// Only a fixed schema vocabulary can be displayed; arbitrary JSON keys may contain secrets.
private val publicImportFields =
    ("type name server port udp password username sni client-fingerprint alpn " +
            "skip-cert-verify tls idle-session-check-interval idle-session-timeout " +
            "min-idle-session disable-reuse tfo mptcp fast-open ip-version smux fingerprint " +
            "name-cert-verify certificate private-key ech-opts shadow-tls-opts restls-opts " +
            "jls-opts client-metadata dialer-proxy interface-name routing-mark " +
            "handshake-timeout udp-mtu recv-window-conn recv-window cwnd bbr-profile cipher " +
            "plugin plugin-opts udp-over-tcp udp-over-tcp-version network ws-opts grpc-opts " +
            "uuid alterId servername h2-opts http-opts packet-addr xudp packet-encoding " +
            "global-padding authenticated-length flow encryption reality-opts ports " +
            "hop-interval up down obfs obfs-password token ip disable-sni reduce-rtt " +
            "congestion-controller udp-relay-mode heartbeat-interval request-timeout " +
            "max-udp-relay-packet-size other parameters structure protocol")
        .split(" ")
        .toSet()

internal fun importFieldCounts(value: JSONObject?): Map<String, Int> {
    if (value == null) return emptyMap()
    val protocols =
        setOf(
            "anytls",
            "socks5",
            "http",
            "ss",
            "ssr",
            "trojan",
            "vmess",
            "vless",
            "hysteria",
            "hysteria2",
            "tuic",
            "snell",
            "mieru",
            "other",
        )
    return value
        .keys()
        .asSequence()
        .take(1024)
        .mapNotNull { key ->
            val parts = key.split('.')
            if (parts.size != 2 || parts[0] !in protocols || parts[1] !in publicImportFields) null
            else value.optInt(key).takeIf { it in 1..MAX_PROFILE_NODES }?.let { key to it }
        }
        .toMap()
}

internal fun importFieldSummary(issues: Map<String, Int>): String =
    issues.toSortedMap().entries.joinToString("；") { (key, count) ->
        val parts = key.split('.')
        val field =
            when (parts[1]) {
                "parameters" -> tr(R.string.parameter_combination)
                "structure" -> tr(R.string.structure_or_data_type)
                "protocol" -> tr(R.string.unknown_protocol)
                "other" -> tr(R.string.other_unrecognized_fields)
                else -> parts[1]
            }
        trPlural(
            R.plurals.count_proxy_proxies_3,
            (count).toInt(),
            if (parts[0] == "other") tr(R.string.other)
            else parts[0].uppercase(java.util.Locale.ROOT),
            field,
            count,
        )
    }
