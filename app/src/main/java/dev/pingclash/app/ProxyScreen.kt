// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pingclash.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun proxyTypeLabel(type: String) =
    when (type) {
        "select" -> "Selector"
        "url-test" -> "URLTest"
        "fallback" -> "Fallback"
        "load-balance" -> "LoadBalance"
        "hysteria2" -> "Hysteria2"
        "vmess" -> "VMess"
        "vless" -> "VLESS"
        "trojan" -> "Trojan"
        "anytls" -> "AnyTLS"
        "ss" -> "Shadowsocks"
        "tuic" -> "TUIC"
        "socks5" -> "SOCKS5"
        else -> type
    }

internal fun LocalProfile.proxyDescription(name: String): String {
    nodes
        .firstOrNull { it.name == name }
        ?.let {
            return proxyTypeLabel(it.type)
        }
    val g =
        groups.firstOrNull { it.name == name }
            ?: return if (name in listOf("DIRECT", "REJECT", "COMPATIBLE")) name else ""
    return proxyTypeLabel(g.type) +
        when {
            g.type == "load-balance" -> tr(R.string.automatically_assigned)
            g.selected.isEmpty() -> tr(R.string.automatically_selected)
            else -> "(${g.selected})"
        }
}

internal fun groupProblem(reason: String) =
    when (reason) {
        "resource_limit" -> tr(R.string.group_dependencies_exceed_local_limits)
        "remote_provider" -> tr(R.string.this_group_depends_on_a_remote_provider)
        "group_filter" -> tr(R.string.unsupported_filter_expression_in_this_grou)
        "group_url" -> tr(R.string.invalid_test_url_in_this_group)
        "group_dependency" -> tr(R.string.missing_duplicate_or_cyclic_group_dependen)
        "group_node_unavailable" -> tr(R.string.the_selected_proxy_failed_configuration_va)
        "group_missing" -> tr(R.string.the_current_path_refers_to_a_missing)
        "group_cycle" -> tr(R.string.the_current_path_has_a_dependency_cycle)
        "group_duplicate" -> tr(R.string.duplicate_path_name_or_conflict_with_a)
        "group_depth" -> tr(R.string.group_nesting_exceeds_local_limits)
        else -> tr(R.string.unsupported_group_type_or_fields)
    }

@Composable
internal fun ProxyScreen(
    data: AppData,
    locked: Boolean,
    model: AppViewModel,
    tests: ProxyTestState,
    onProfiles: () -> Unit,
    onDiagnosis: () -> Unit,
) {
    // A tap shows at once; the queue saves it in the background ([SelectionQueue]).
    val choices by model.selections.choices.collectAsState()
    @Suppress("NAME_SHADOWING") val data = remember(data, choices) { data.withChoices(choices) }
    val profile = data.activeProfile
    // The definition is parsed once per profile version, not on every tap that changes a group.
    val hidden =
        remember(profile?.id, profile?.definition) { profile?.hiddenGroupNames().orEmpty() }
    val groups =
        remember(profile?.id, hidden, profile?.groups, profile?.nodes) {
            profile?.displayGroups(hidden).orEmpty()
        }
    val live = rememberGroupLive(model, profile, data.selectedId)
    // Latency without a button: test the profile in the background when its results are missing
    // or old. Checked again when a test ends, so a cleared profile is measured again.
    // Checked again when the page unlocks; marked as tested only once a test has really started.
    LaunchedEffect(profile?.id, profile?.definition, tests.running, locked) {
        val now = System.currentTimeMillis()
        if (profile != null && !locked && ProxyAutoTest.due(profile, tests, now)) {
            // DIRECT too, where a group offers it: it is a way out like the nodes.
            val direct =
                DIRECT_OUTBOUND.takeIf { name -> profile.groups.any { name in it.proxies } }
            if (model.testProxies(profile, profile.nodes.map { it.name } + listOfNotNull(direct)))
                ProxyAutoTest.started(profile, now)
        }
    }
    val inheritedNotices = LocalIssueNotices.current
    val notices = inheritedNotices ?: remember { IssueNoticeCenter() }
    // The groups opened, deepest last. Each is a sheet over the one it was opened from, and
    // closing it shows that one again.
    var stack by rememberSaveable(profile?.id) { mutableStateOf("") }
    val opened = stack.split(GROUP_STACK_SEPARATOR).filter { it.isNotEmpty() }
    var detail by remember(profile?.id) { mutableStateOf<ProxyDetailTarget?>(null) }
    val sort = rememberSaveable { mutableStateOf(ProxySort.SUBSCRIPTION) }
    val hideFailed = rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalIssueNotices provides notices) {
            val state = profile?.let { ProxyPageState(it, data, tests, live, locked) }
            val actions =
                state?.let {
                    proxyActions(
                        model,
                        it,
                        onDiagnosis,
                        { name ->
                            if (name !in opened)
                                stack = (opened + name).joinToString(GROUP_STACK_SEPARATOR)
                        },
                    ) { target ->
                        detail = target
                    }
                }
            val pool = groups.singleOrNull { it.type == "nodes" }
            val route = state?.let { proxyRoute(data, it.profile, it.live, pool) }
            Column(Modifier.fillMaxSize().testTag("proxy-page")) {
                ProxyTopBar(state, route, actions, model, ProxyPages(onProfiles, onDiagnosis))
                val ready = state != null && actions != null && route != null
                if (ready && groups.isNotEmpty())
                    ProxyRouteList(
                        requireNotNull(state),
                        requireNotNull(route),
                        groups,
                        requireNotNull(actions),
                    )
                else ProxyEmpty(profile, onProfiles)
            }
            // A group a refresh removed closes, with the ones opened from it, and stays closed if
            // a group of that name comes back later.
            val gone =
                if (state == null) -1
                else
                    opened.indexOfFirst { name -> pool?.name != name && state.groups[name] == null }
            if (gone >= 0)
                LaunchedEffect(gone, stack) {
                    stack = opened.take(gone).joinToString(GROUP_STACK_SEPARATOR)
                }
            if (state != null && actions != null)
                opened.forEachIndexed { depth, name ->
                    val group = pool?.takeIf { it.name == name } ?: state.groups[name]
                    if (group != null)
                        key(depth, name) {
                            StackedGroupSheet(
                                state,
                                group,
                                actions,
                                GroupSheetHost(model, sort, hideFailed),
                            ) {
                                stack = opened.take(depth).joinToString(GROUP_STACK_SEPARATOR)
                            }
                        }
                }
            if (state != null)
                detail?.let { target ->
                    // The sheet stays open, so returning from the diagnosis lands where it left.
                    ProxyDetailHost(state, target, model, { detail = null }, onDiagnosis)
                }
        }
        if (inheritedNotices == null) IssueNoticeHost(notices)
    }
}

/** Joins the names of the opened groups; group names never contain it. */
private const val GROUP_STACK_SEPARATOR = "\u0000"

/** The two pages the Proxies bar can open. */
private class ProxyPages(val profiles: () -> Unit, val diagnosis: () -> Unit)

/** How often the page asks the running core what its groups use. */
private const val LIVE_REFRESH_MS = 3000L

/** What the running core reports about the groups, while the VPN is up; empty otherwise. */
@Composable
private fun rememberGroupLive(
    model: AppViewModel,
    profile: LocalProfile?,
    selectedId: String?,
): GroupLive {
    val vpn by model.vpn.collectAsState()
    val established = vpn.stage == VpnPhase.ESTABLISHED
    var live by remember { mutableStateOf(GroupLive.EMPTY) }
    // Asked only while the app is on screen; the last answer stays until it is back.
    val foreground = rememberForeground()
    LaunchedEffect(established, profile?.definition, selectedId, foreground) {
        if (!established || !model.nativeReady) {
            live = GroupLive.EMPTY
            return@LaunchedEffect
        }
        if (!foreground) return@LaunchedEffect
        while (true) {
            live =
                withContext(Dispatchers.IO) {
                    runCatching { GroupLive.parse(Mobile.vpnGroupState()) }
                        .getOrDefault(GroupLive.EMPTY)
                }
            delay(LIVE_REFRESH_MS)
        }
    }
    return live
}

// While the page is locked (an operation, or the VPN connecting or stopping) nothing is chosen
// and no test starts; a running test can still be cancelled.
private fun proxyActions(
    model: AppViewModel,
    state: ProxyPageState,
    onDiagnosis: () -> Unit,
    open: (String) -> Unit,
    details: (ProxyDetailTarget) -> Unit,
): ProxyPageActions {
    val profile = state.profile
    fun choose(group: LocalGroup, name: String) {
        if (state.locked) return
        if (group.type == "nodes")
            profile.nodes.firstOrNull { it.name == name }?.let { model.choose(profile, "", it.id) }
        else if (!group.spread()) model.choose(profile, group.name, name)
    }
    return ProxyPageActions(
        open = open,
        choose = ::choose,
        test = { group, names ->
            if (names.isEmpty()) model.cancelProxyTests()
            else if (!state.locked) model.testProxies(profile, names, group.name)
        },
        details = { group, name -> details(ProxyDetailTarget(group.name, name)) },
        help = { help ->
            when (help) {
                is RouteHelp.Switch -> choose(help.group, help.node)
                RouteHelp.Doctor -> {
                    model.doctor.start()
                    onDiagnosis()
                }
            }
        },
    )
}

@Composable
private fun ProxyTopBar(
    state: ProxyPageState?,
    route: ProxyRoute?,
    actions: ProxyPageActions?,
    model: AppViewModel,
    pages: ProxyPages,
) {
    val warning = compatibilityWarning(state?.profile, null)
    var warningOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageHeading(tr(R.string.proxies), Modifier.weight(1f), "page-title-proxies")
        CompatibilityWarningBadge(state?.profile?.id.orEmpty(), warning, warningOpen) {
            warningOpen = it
        }
        val first = route?.stops?.firstOrNull()?.group
        if (state != null && actions != null && first != null)
            IconButton(
                { actions.test(first, if (state.tests.running) emptyList() else first.proxies) },
                enabled = state.tests.running || !state.locked,
                modifier =
                    Modifier.testTag("proxy-test-route").semantics {
                        contentDescription =
                            if (state.tests.running) tr(R.string.cancel_tests)
                            else tr(R.string.test_current_group)
                    },
            ) {
                if (state.tests.running)
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ProxySymbol("bolt", color = pageInk().text)
            }
        ProxyPageMenu(state, warning, model, pages) { warningOpen = true }
    }
}

@Composable
private fun ProxyPageMenu(
    state: ProxyPageState?,
    warning: String,
    model: AppViewModel,
    pages: ProxyPages,
    showWarning: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    RevealMenuButton(
        menu,
        { menu = it },
        Modifier.testTag("proxy-menu").semantics { contentDescription = tr(R.string.proxy_options) },
    ) {
        if (warning.isNotBlank())
            GlassMenuItem(
                { Text(tr(R.string.compatibility_warning)) },
                {
                    menu = false
                    showWarning()
                },
            )
        GlassMenuItem(
            { Text(tr(R.string.manage_profiles)) },
            {
                menu = false
                pages.profiles()
            },
        )
        GlassMenuItem(
            { Text(tr(R.string.diagnose_main_proxy)) },
            {
                menu = false
                model.doctor.start()
                pages.diagnosis()
            },
            // Like the doctor's own page: a running latency test gives way, and a check already
            // going is replaced by this one.
            enabled = state?.data?.selected != null,
        )
    }
}

/** Corner radius of the group list's card, matching the route card. */
private val LIST_CORNER = 14.dp

/** The route card, then every group not already on the route, in the subscription's order. */
@Composable
private fun ProxyRouteList(
    state: ProxyPageState,
    route: ProxyRoute,
    groups: List<LocalGroup>,
    actions: ProxyPageActions,
) {
    val onRoute = route.stops.map { it.group.name }.toSet()
    val others = groups.filter { it.name !in onRoute }
    LazyColumn(
        Modifier.fillMaxSize().testTag("proxy-groups"),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = LocalNavigationClearance.current + 18.dp,
            ),
    ) {
        if (route.stops.isNotEmpty() || route.exit != null)
            item(key = "route") { RouteCard(state, route, actions) }
        if (others.isNotEmpty()) item(key = "gap") { Spacer(Modifier.height(14.dp)) }
        itemsIndexed(others, key = { _, group -> group.name }) { index, group ->
            val top = if (index == 0) LIST_CORNER else 0.dp
            val bottom = if (index == others.lastIndex) LIST_CORNER else 0.dp
            Surface(
                shape = RoundedCornerShape(top, top, bottom, bottom),
                color = Panel.copy(alpha = .9f),
            ) {
                Column {
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                    GroupRow(state, group, actions)
                }
            }
        }
    }
}
