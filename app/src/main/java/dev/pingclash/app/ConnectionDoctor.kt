// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.diagnostics.Models
import dev.pingclash.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The steps of the connection doctor, in the order they run. */
internal enum class DoctorStage {
    PHONE,
    NODE,
    PEERS,
    WEBSITES,
    IP,
}

/** Websites the doctor checks: the ones Home shows first. */
internal const val DOCTOR_WEBSITES = 4

/**
 * What the doctor found: one problem and, where it helps, one thing to do about that problem, as
 * string resources; [code] is the short token of the error details, [arg] fills a string that names
 * something.
 */
internal data class DoctorFinding(
    val cause: Int,
    val next: Int?,
    val code: String = "",
    val arg: String? = null,
)

/** Everything the doctor gathered; nothing of it is kept after the page closes. */
internal data class DoctorEvidence(
    val selected: Boolean,
    val vpnActive: Boolean,
    val mode: String,
    val steps: List<Models.Step>?,
    val websites: List<Latency?>,
    val exitChecked: Boolean,
    val plan: PlanCheck? = null,
    val clock: ClockCheck? = null,
    val phone: PhoneCheck? = null,
    /** Other nodes, checked only when the selected one failed; null when not checked. */
    val peers: List<PeerResult>? = null,
    val dns: DnsCheck? = null,
    /** The selected node's protocol and transport, for the support line. */
    val protocol: String = "",
    val nodeName: String = "",
    /** The group the check started from when it was followed to [nodeName]; empty otherwise. */
    val group: String = "",
    /** Carrier, country and (with location already allowed) province, read once during the run. */
    val region: RegionInfo? = null,
    /** The exit IP as measured during the run; null when it was not checked. */
    val exit: ExitInfo? = null,
)

/**
 * Nothing beyond the phone can work: no network, a sign-in page, a plan that ended. Every later
 * check would only fail too and blur the answer, so the run stops there.
 */
internal val DoctorEvidence.blocked: Boolean
    get() =
        phone?.let { it.offline || it.captive } == true ||
            plan?.let { it.expired || it.usedUp } == true

internal data class DoctorState(
    val running: Boolean = false,
    val stage: DoctorStage? = null,
    val evidence: DoctorEvidence? = null,
)

/** Websites that answered, were restricted, and were checked (a result of any kind). */
internal data class WebsiteTally(val reachable: Int, val restricted: Int, val checked: Int)

internal fun tallyWebsites(results: List<Latency?>): WebsiteTally =
    WebsiteTally(
        reachable = results.count { it is Latency.Measured },
        restricted =
            results.count { it is Latency.Failed && it.reason == WebsiteFailure.RESTRICTED },
        checked =
            results.count {
                it is Latency.Measured ||
                    it is Latency.Failed && it.reason != WebsiteFailure.CANCELLED
            },
    )

internal val INCOMPLETE = setOf(Models.State.CANCELLED, Models.State.BLOCKED, Models.State.STALE)

/** The first failing stage of the node's own check, mapped to a cause and a next step. */
internal fun nodeFinding(steps: List<Models.Step>): DoctorFinding? {
    val failed = steps.firstOrNull { it.state == Models.State.FAILED } ?: return null
    val code = failed.code.name
    return when (failed.stage) {
        Models.Stage.NETWORK ->
            DoctorFinding(R.string.doctor_cause_network, R.string.doctor_next_network, code)
        Models.Stage.DNS -> DoctorFinding(R.string.doctor_cause_dns, R.string.doctor_next_dns, code)
        Models.Stage.TCP ->
            DoctorFinding(R.string.doctor_cause_tcp, R.string.doctor_next_node, code)
        Models.Stage.TLS -> DoctorFinding(R.string.doctor_cause_tls, R.string.doctor_next_tls, code)
        else -> DoctorFinding(R.string.doctor_cause_proxy, R.string.doctor_next_update, code)
    }
}

/** What the websites say once the node itself works. */
internal fun websiteFinding(evidence: DoctorEvidence): DoctorFinding {
    val sites = tallyWebsites(evidence.websites)
    val code = "SITES_${sites.reachable}/${sites.checked}"
    return when {
        sites.checked > 0 && sites.reachable == 0 && evidence.mode == "direct" ->
            DoctorFinding(R.string.doctor_cause_sites_direct, R.string.doctor_next_mode, code)
        sites.checked > 0 && sites.reachable == 0 ->
            DoctorFinding(R.string.doctor_cause_sites, R.string.doctor_next_rules, code)
        sites.restricted > 0 ->
            DoctorFinding(R.string.doctor_cause_restricted, R.string.doctor_next_region, code)
        sites.reachable < sites.checked ->
            DoctorFinding(R.string.doctor_cause_some_sites, R.string.doctor_next_connections, code)
        else -> DoctorFinding(R.string.doctor_cause_ok, null, "OK")
    }
}

/** The node line of the summary: its proxy result, or the stage that failed. */
internal fun doctorNodeLine(steps: List<Models.Step>?): String {
    val failed = steps?.firstOrNull { it.state == Models.State.FAILED }
    val proxy = steps?.lastOrNull { it.stage == Models.Stage.PROXY }
    return when {
        failed != null -> tr(failedStage(failed.stage))
        proxy?.state == Models.State.PASSED -> tr(R.string.doctor_node_ok, proxy.elapsedMs)
        else -> tr(R.string.doctor_not_checked)
    }
}

/** "TCP failed": the failed stage in a few words, so the row stays on one line. */
private fun failedStage(stage: Models.Stage): Int =
    when (stage) {
        Models.Stage.NETWORK -> R.string.doctor_node_failed_network
        Models.Stage.DNS -> R.string.doctor_node_failed_dns
        Models.Stage.TCP -> R.string.doctor_node_failed_tcp
        Models.Stage.TLS -> R.string.doctor_node_failed_tls
        else -> R.string.doctor_node_failed_proxy
    }

internal fun doctorWebsitesLine(results: List<Latency?>): String {
    val sites = tallyWebsites(results)
    return if (sites.checked == 0) tr(R.string.doctor_not_checked)
    else tr(R.string.doctor_websites_line, sites.reachable, sites.checked)
}

/** How long each other node may take to answer. */
private const val PEER_TIMEOUT_MS = 8_000

/** How long a replaced run's node check may take to stop. */
private const val STOP_WAIT_MS = 5_000L

/** Most a whole doctor run may take; each check also has its own timeout. */
private const val DOCTOR_LIMIT_MS = 120_000L

/**
 * Runs the existing checks one after another (the node's diagnosis, the websites, the IP) and keeps
 * what they found for [doctorFinding]. It starts nothing a check would refuse anyway: a busy app
 * leaves the node unchecked and the conclusion says to try again.
 */
internal class ConnectionDoctor(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val model: AppViewModel,
) {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(DoctorState())
    val state = mutable.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null

    /**
     * Starts a run on [target] (a node or group id of the active profile), or the main proxy. A run
     * already going is replaced, so the newest request is the one answered.
     */
    fun start(target: String? = null) {
        val previous = job
        job =
            scope.launch {
                if (previous?.isActive == true) {
                    previous.cancelAndJoin()
                    stopChecks()
                    // The replaced run's node check has to end before a new one can start.
                    kotlinx.coroutines.withTimeoutOrNull(STOP_WAIT_MS) {
                        model.diagnosis.first { !it.running }
                    }
                }
                mutable.value = DoctorState(running = true, stage = DoctorStage.PHONE)
                // The target as asked for, so a run that runs out of time still says what it was
                // checking, with whatever it had found by then.
                val requested =
                    model.data.value.let { now -> target?.let { now.copy(selectedId = it) } ?: now }
                var found = timedOut(requested)
                val evidence =
                    kotlinx.coroutines.withTimeoutOrNull(DOCTOR_LIMIT_MS) {
                        gather(requested) { found = it }
                    } ?: stopChecks() ?: found
                mutable.value = DoctorState(evidence = evidence)
            }
    }

    /** Stops a run and the checks it started; checks the user started stay untouched. */
    fun cancel() {
        if (job?.isActive != true) return
        job?.cancel()
        stopChecks()
        mutable.value = DoctorState()
    }

    /** Ends the diagnosis and website checks a run left behind; null, so a timeout reads on. */
    private fun stopChecks(): DoctorEvidence? {
        model.cancelDiagnosis()
        model.serviceChecks.cancel()
        return null
    }

    /** What a run that ran out of time before the phone checks knows: its target. */
    private fun timedOut(requested: AppData): DoctorEvidence {
        val target = requested.selected
        return DoctorEvidence(
            target != null,
            VpnRuntime.state.value.stage == VpnPhase.ESTABLISHED,
            requested.client.mode,
            null,
            emptyList(),
            false,
            protocol = nodeProtocol(target),
            nodeName = target?.name.orEmpty(),
        )
    }

    /** Runs the checks on [requested]; [progress] gets what is known after the phone checks. */
    private suspend fun gather(
        requested: AppData,
        progress: (DoctorEvidence) -> Unit,
    ): DoctorEvidence {
        // A latency test running would keep the node's diagnosis from starting: the doctor was
        // asked for, so the test gives way.
        if (model.proxyTests.value.running) {
            model.cancelProxyTests()
            model.proxyTests.first { !it.running }
        }
        val data = followGroup(requested)
        val app = model.getApplication<PingClashApplication>()
        val common = phoneEvidence(app, requested, data)
        progress(common)
        if (common.blocked) return common
        mutable.value = DoctorState(running = true, stage = DoctorStage.NODE)
        val steps = if (common.selected) diagnoseNode(data) else null
        // Other nodes and the DNS answers only explain a failure; a working node needs neither.
        val failed = steps?.any { it.state == Models.State.FAILED } == true
        mutable.value = DoctorState(running = true, stage = DoctorStage.PEERS)
        val peers = if (failed) probePeers(data, PEER_TIMEOUT_MS) else null
        val dns =
            if (failed) dnsCheck(app, data.selected, data.dnsAddress, data.client.dnsHttps)
            else null
        mutable.value = DoctorState(running = true, stage = DoctorStage.WEBSITES)
        val ids = serviceTargets.take(DOCTOR_WEBSITES).map { it.id }
        model.serviceChecks.start(model.data.value.client.probeTimeout, ids)
        val sites = model.serviceChecks.state.first { !it.running }.values
        val vpnActive = VpnRuntime.state.value.stage == VpnPhase.ESTABLISHED
        var exit: ExitInfo? = null
        if (vpnActive) {
            mutable.value = DoctorState(running = true, stage = DoctorStage.IP)
            model.refreshExit(force = true)
            // Kept with the report: a later reconnect or node change does not rewrite it.
            exit = model.exit.first { !it.checking }.info
        }
        return common.copy(
            vpnActive = vpnActive,
            steps = steps,
            websites = ids.map { sites[it] },
            exitChecked = exit != null,
            peers = peers,
            dns = dns,
            exit = exit,
        )
    }

    /**
     * The plan, the clock, the phone and its region, read off the main thread, with what the run is
     * about. A group [requested] is named next to the node [data] followed it to.
     */
    private suspend fun phoneEvidence(
        app: PingClashApplication,
        requested: AppData,
        data: AppData,
    ): DoctorEvidence {
        val group = requested.selected?.takeIf { it.type == "group" }?.name
        return withContext(Dispatchers.IO) {
            DoctorEvidence(
                data.selected != null,
                VpnRuntime.state.value.stage == VpnPhase.ESTABLISHED,
                data.client.mode,
                null,
                emptyList(),
                false,
                planCheck(data.activeProfile),
                clockCheck(app),
                phoneCheck(app),
                protocol = nodeProtocol(data.selected),
                nodeName = data.selected?.name.orEmpty(),
                group = group?.takeIf { it != data.selected?.name }.orEmpty(),
                region = regionInfo(app),
            )
        }
    }

    /**
     * [data] with a group target swapped for the node the group uses now, so that node's server and
     * its neighbours are what get compared; a group whose pick is not known stays a group.
     */
    private fun followGroup(data: AppData): AppData {
        val target = data.selected?.takeIf { it.type == "group" }
        val live =
            if (VpnRuntime.state.value.stage == VpnPhase.ESTABLISHED)
                runCatching { GroupLive.parse(Mobile.vpnGroupState()) }
                    .getOrDefault(GroupLive.EMPTY)
            else GroupLive.EMPTY
        val profile = data.activeProfile
        val node =
            target
                ?.let { profile?.exitOf(it.name, live) }
                ?.let { exit -> profile?.nodes?.firstOrNull { it.name == exit } }
        return node?.let { data.copy(selectedId = it.id) } ?: data
    }

    /** The node's steps from a diagnosis started now, or null when it could not start. */
    private suspend fun diagnoseNode(data: AppData): List<Models.Step>? {
        val before = model.diagnosis.value.report
        model.diagnoseData(data)
        if (!model.diagnosis.value.running) return null
        val done = model.diagnosis.first { !it.running }
        return done.report?.takeIf { it !== before }?.steps
    }
}
