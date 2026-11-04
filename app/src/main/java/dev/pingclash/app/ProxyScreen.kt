// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

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

// A tab's members come only from that tab. Never append the profile's node pool
// or flatten nested groups into their parent. A groupless profile gets one pool.
internal fun LocalProfile.displayGroups(): List<LocalGroup> {
    if (groups.isEmpty()) {
        if (hasSubscriptionGroups) return emptyList()
        return listOf(
            LocalGroup(tr(R.string.all_proxies), "nodes", nodes.map { it.name }, "", true)
        )
    }
    val hidden =
        runCatching {
                val raw = JSONObject(definition).optJSONArray("proxy-groups")
                if (raw == null) emptySet()
                else
                    (0 until raw.length())
                        .mapNotNull {
                            raw.optJSONObject(it)
                                ?.takeIf { it.optBoolean("hidden") }
                                ?.optString("name")
                        }
                        .toSet()
            }
            .getOrDefault(emptySet())
    return groups.filter { it.name !in hidden }
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
    val profile = data.activeProfile
    val nodesByName = remember(profile?.nodes) { profile?.nodes.orEmpty().associateBy { it.name } }
    val groupsByName =
        remember(profile?.groups) { profile?.groups.orEmpty().associateBy { it.name } }
    val descriptions =
        remember(profile?.nodes, profile?.groups) {
            profile
                ?.let { p ->
                    p.nodes.associate { it.name to proxyTypeLabel(it.type) } +
                        p.groups.associate { it.name to p.proxyDescription(it.name) }
                }
                .orEmpty()
        }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(ProxySort.SUBSCRIPTION.name) }
    var hideFailed by rememberSaveable { mutableStateOf(false) }
    val sort = ProxySort.entries.firstOrNull { it.name == sortName } ?: ProxySort.SUBSCRIPTION
    var singleColumn by rememberProxyListLayout()
    val groups =
        remember(profile?.id, profile?.definition, profile?.groups, profile?.nodes) {
            profile?.displayGroups().orEmpty()
        }
    val inheritedNotices = LocalIssueNotices.current
    val notices = inheritedNotices ?: remember { IssueNoticeCenter() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalIssueNotices provides notices) {
            key(profile?.id) {
                var detail by remember { mutableStateOf<ProxyDetailTarget?>(null) }
                val pager = rememberPagerState(pageCount = { groups.size })
                val scope = rememberCoroutineScope()
                val tabs = rememberLazyListState()
                val current = groups.getOrNull(pager.currentPage)
                val warning = compatibilityWarning(profile, current)
                var warningOpen by remember { mutableStateOf(false) }
                LaunchedEffect(pager.currentPage, groups.map { it.name }) {
                    if (groups.isNotEmpty()) tabs.animateScrollToItem(pager.currentPage)
                }
                Column(Modifier.fillMaxSize().testTag("proxy-page")) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PageHeading(tr(R.string.proxies), Modifier.weight(1f), "page-title-proxies")
                        CompatibilityWarningBadge(profile?.id.orEmpty(), warning, warningOpen) {
                            warningOpen = it
                        }
                        Surface(shape = CircleShape, color = Panel.copy(alpha = .72f)) {
                            Row {
                                IconButton(
                                    {
                                        searching = !searching
                                        if (!searching) query = ""
                                    },
                                    Modifier.semantics {
                                        contentDescription = tr(R.string.search_proxies)
                                    },
                                ) {
                                    AppGlyph(PcIcon.Search, Modifier.size(23.dp))
                                }
                                IconButton(
                                    {
                                        if (tests.running) model.cancelProxyTests()
                                        else if (profile != null && current != null)
                                            model.testProxies(
                                                profile,
                                                current.proxies,
                                                current.name,
                                            )
                                    },
                                    enabled =
                                        tests.running ||
                                            profile != null &&
                                                current?.proxies?.any { name ->
                                                    profile.nodes.any { it.name == name } ||
                                                        profile.groups.any {
                                                            it.name == name && it.supported
                                                        }
                                                } == true,
                                    modifier =
                                        Modifier.testTag("proxy-test-group").semantics {
                                            contentDescription =
                                                if (tests.running) tr(R.string.cancel_tests)
                                                else tr(R.string.test_current_group)
                                        },
                                ) {
                                    if (tests.running)
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    else ProxySymbol("bolt")
                                }
                            }
                        }
                        Box {
                            IconButton(
                                { menu = true },
                                Modifier.testTag("proxy-menu").semantics {
                                    contentDescription = tr(R.string.proxy_options)
                                },
                            ) {
                                ProxySymbol("menu")
                            }
                            DropdownMenu(menu, { menu = false }) {
                                if (warning.isNotBlank())
                                    DropdownMenuItem(
                                        { Text(tr(R.string.compatibility_warning)) },
                                        {
                                            menu = false
                                            warningOpen = true
                                        },
                                    )
                                ProxyLayoutOptions(singleColumn) {
                                    singleColumn = it
                                    menu = false
                                }
                                ProxySort.entries.forEach { value ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                tr(
                                                    when (value) {
                                                        ProxySort.SUBSCRIPTION ->
                                                            R.string.proxy_sort_subscription
                                                        ProxySort.NAME -> R.string.proxy_sort_name
                                                        ProxySort.LATENCY ->
                                                            R.string.proxy_sort_latency
                                                    }
                                                )
                                            )
                                        },
                                        leadingIcon = { RadioButton(sort == value, null) },
                                        onClick = {
                                            sortName = value.name
                                            menu = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(tr(R.string.proxy_hide_failed)) },
                                    leadingIcon = { Checkbox(hideFailed, null) },
                                    onClick = {
                                        hideFailed = !hideFailed
                                        menu = false
                                    },
                                )
                                DropdownMenuItem(
                                    { Text(tr(R.string.latency_tcp_test)) },
                                    {
                                        menu = false
                                        if (profile != null && current != null)
                                            model.testTcpProxies(
                                                profile,
                                                current.proxies,
                                                current.name,
                                            )
                                    },
                                    enabled = !tests.running,
                                )
                                DropdownMenuItem(
                                    { Text((tr(R.string.manage_profiles))) },
                                    {
                                        menu = false
                                        onProfiles()
                                    },
                                )
                                val isMain =
                                    current != null &&
                                        data.selectedId == profile?.groupId(current.name)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            tr(
                                                if (isMain)
                                                    R.string.current_group_is_already_the_main_proxy
                                                else R.string.use_current_group_as_main_proxy
                                            )
                                        )
                                    },
                                    onClick = {
                                        menu = false
                                        if (profile != null && current != null)
                                            model.selectMainGroup(profile, current)
                                    },
                                    enabled =
                                        !locked &&
                                            current?.supported == true &&
                                            current.type != "nodes",
                                    modifier = Modifier.testTag("group-main-${current?.name}"),
                                )
                                if (current?.type in listOf("url-test", "fallback"))
                                    DropdownMenuItem(
                                        { Text((tr(R.string.restore_automatic_selection))) },
                                        {
                                            menu = false
                                            model.selectGroup(
                                                requireNotNull(profile),
                                                requireNotNull(current),
                                                "",
                                            )
                                        },
                                        enabled = !locked,
                                    )
                                DropdownMenuItem(
                                    { Text(tr(R.string.diagnose_main_proxy)) },
                                    {
                                        menu = false
                                        model.diagnose()
                                        onDiagnosis()
                                    },
                                    enabled = data.selected != null && !tests.running,
                                )
                            }
                        }
                    }
                    if (searching)
                        OutlinedTextField(
                            query,
                            { if (it.length <= 100) query = it },
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 10.dp)
                                .testTag("proxy-search"),
                            singleLine = true,
                            shape = CircleShape,
                            placeholder = { Text((tr(R.string.search_this_group))) },
                            trailingIcon = {
                                TextButton({
                                    searching = false
                                    query = ""
                                }) {
                                    Text((tr(R.string.close)))
                                }
                            },
                        )
                    if (profile == null || groups.isEmpty()) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppGlyph(PcIcon.Globe, Modifier.size(44.dp), Mint)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                if (profile == null) tr(R.string.no_proxy_profiles_yet)
                                else tr(R.string.original_policy_groups_are_unavailable_or),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(onProfiles) {
                                Text((tr(R.string.add_or_manage_profiles)))
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LazyRow(
                                state = tabs,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(start = 16.dp, end = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(groups.size, key = { groups[it].name }) { i ->
                                    val g = groups[i]
                                    val tabColor by
                                        animateColorAsState(
                                            if (pager.currentPage == i)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else Panel,
                                            if (LocalMotionAllowed.current) tween(180) else snap(),
                                            label = "group-selection",
                                        )
                                    Surface(
                                        shape = CircleShape,
                                        color = tabColor,
                                        contentColor = if (pager.currentPage == i) Mint else Muted,
                                        border =
                                            BorderStroke(
                                                .7.dp,
                                                if (pager.currentPage == i) Mint.copy(alpha = .2f)
                                                else MaterialTheme.colorScheme.outlineVariant,
                                            ),
                                        modifier =
                                            Modifier.testTag("group-tab-${g.name}")
                                                .semantics { selected = pager.currentPage == i }
                                                .clickable {
                                                    scope.launch { pager.animateScrollToPage(i) }
                                                },
                                    ) {
                                        Text(
                                            g.name,
                                            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                            maxLines = 1,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                            IconButton(
                                { chooser = true },
                                Modifier.padding(end = 8.dp)
                                    .background(Panel, CircleShape)
                                    .semantics {
                                        contentDescription = tr(R.string.all_policy_groups)
                                    },
                            ) {
                                ProxySymbol("down")
                            }
                        }
                        HorizontalPager(
                            pager,
                            Modifier.weight(1f).fillMaxWidth().testTag("strategy-pager"),
                            key = { groups[it].name },
                            beyondViewportPageCount = 0,
                        ) { i ->
                            val group = groups[i]
                            val members =
                                orderedProxyMembers(
                                    group.proxies.filter {
                                        query.isBlank() ||
                                            it.contains(query, true) ||
                                            descriptions[it].orEmpty().contains(query, true)
                                    },
                                    sort,
                                    hideFailed,
                                ) {
                                    tests.delayFor(profile.id, it, group.name)
                                }
                            Column(Modifier.fillMaxSize()) {
                                if (members.isEmpty())
                                    Box(
                                        Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            (tr(R.string.no_matching_members_in_this_group)),
                                            color = Muted,
                                        )
                                    }
                                else {
                                    val wide =
                                        singleColumn || LocalDensity.current.fontScale >= 1.6f
                                    val columns = if (wide) 1 else 2
                                    LazyVerticalGrid(
                                        // The phone's one or two columns; tablets fit more.
                                        GridCells.Adaptive(
                                            if (wide) WIDE_MEMBER_WIDTH else MEMBER_WIDTH
                                        ),
                                        modifier =
                                            Modifier.fillMaxSize()
                                                .testTag("group-members-${group.name}"),
                                        contentPadding =
                                            PaddingValues(
                                                start = 16.dp,
                                                end = 16.dp,
                                                top = 4.dp,
                                                bottom = LocalNavigationClearance.current + 18.dp,
                                            ),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(members, key = { it }) { name ->
                                            val node = nodesByName[name]
                                            val nested = groupsByName[name]
                                            val available =
                                                name in listOf("DIRECT", "REJECT", "COMPATIBLE") ||
                                                    node != null ||
                                                    nested?.supported == true
                                            val chosen =
                                                if (group.type == "nodes")
                                                    node?.id == data.selectedId
                                                else group.selected == name
                                            val card =
                                                ProxyMemberPresentation(
                                                    name,
                                                    descriptions[name] ?: name,
                                                    chosen,
                                                    !locked && group.selectionEditable && available,
                                                    tests.delayFor(profile.id, name, group.name),
                                                    columns == 1,
                                                )
                                            ProxyMemberCard(
                                                card,
                                                ProxyMemberActions(
                                                    select = {
                                                        when (group.type) {
                                                            "nodes" ->
                                                                node?.let { model.select(it.id) }
                                                            "load-balance" ->
                                                                model.message(
                                                                    tr(
                                                                        R.string
                                                                            .the_core_assigns_connections_in_load_balan
                                                                    )
                                                                )
                                                            else ->
                                                                model.toggleGroupMember(
                                                                    profile.id,
                                                                    group.name,
                                                                    name,
                                                                )
                                                        }
                                                    },
                                                    details = {
                                                        detail = ProxyDetailTarget(group.name, name)
                                                    },
                                                    test =
                                                        if (
                                                            (node != null ||
                                                                nested?.supported == true) &&
                                                                !tests.running
                                                        ) {
                                                            {
                                                                model.testProxies(
                                                                    profile,
                                                                    listOf(name),
                                                                    group.name,
                                                                )
                                                            }
                                                        } else null,
                                                    open =
                                                        if (nested != null) {
                                                            {
                                                                val index =
                                                                    groups.indexOfFirst {
                                                                        it.name == name
                                                                    }
                                                                if (index >= 0)
                                                                    scope.launch {
                                                                        pager.animateScrollToPage(
                                                                            index
                                                                        )
                                                                    }
                                                            }
                                                        } else null,
                                                ),
                                                Modifier.testTag("group-choice-${group.name}-$name"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                detail?.let { target ->
                    val group = groups.firstOrNull { it.name == target.group }
                    val node = profile?.nodes?.firstOrNull { it.name == target.name }
                    val nested =
                        profile?.groups?.firstOrNull { it.name == target.name && it.supported }
                    if (profile != null && group != null && target.name in group.proxies) {
                        val description =
                            profile.proxyDescription(target.name).ifBlank {
                                tr(R.string.member_unavailable)
                            }
                        val state =
                            ProxyMemberPresentation(
                                target.name,
                                description,
                                false,
                                false,
                                tests.delayFor(profile.id, target.name, group.name),
                                singleColumn,
                            )
                        val canTest =
                            !locked && tests.memberFinished(profile.id, target.name, group.name)
                        val available = node != null || nested != null
                        val tcpAvailable =
                            node != null && node.type !in setOf("hysteria", "hysteria2", "tuic")
                        ProxyMemberDetails(
                            state,
                            tests.tcpValues[delayKey(profile.id, target.name, group.name)],
                            node?.type in setOf("hysteria", "hysteria2", "tuic"),
                            ProxyDetailActions(
                                dismiss = { detail = null },
                                urlTest =
                                    if (available && canTest && !tests.running) {
                                        {
                                            model.testProxies(
                                                profile,
                                                listOf(target.name),
                                                group.name,
                                            )
                                        }
                                    } else null,
                                tcpTest =
                                    if (
                                        tcpAvailable &&
                                            canTest &&
                                            tests.tcpValues[
                                                    delayKey(profile.id, target.name, group.name)]
                                                ?.state != "running"
                                    ) {
                                        { model.testTcpMember(profile, target.name, group.name) }
                                    } else null,
                                diagnose =
                                    if (available && canTest) {
                                        {
                                            detail = null
                                            model.diagnoseTarget(
                                                profile.id,
                                                target.name,
                                                group.name,
                                            )
                                            onDiagnosis()
                                        }
                                    } else null,
                            ),
                        )
                    }
                }
                if (chooser)
                    AlertDialog(
                        onDismissRequest = { chooser = false },
                        title = { Text((tr(R.string.proxy_groups))) },
                        text = {
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(groups.size) { i ->
                                    TextButton(
                                        {
                                            chooser = false
                                            scope.launch { pager.animateScrollToPage(i) }
                                        },
                                        Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            groups[i].name,
                                            Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (i == pager.currentPage) Text("✓")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton({ chooser = false }) { Text((tr(R.string.close))) }
                        },
                    )
            }
        }
        if (inheritedNotices == null) IssueNoticeHost(notices)
    }
}

@Composable
internal fun ProxySymbol(
    kind: String,
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalContentColor.current,
) {
    AppGlyph(
        when (kind) {
            "check" -> PcIcon.Check
            "menu" -> PcIcon.More
            "down" -> PcIcon.ChevronDown
            "right" -> PcIcon.ChevronRight
            else -> PcIcon.Bolt
        },
        modifier,
        color,
    )
}

/** Narrowest member card: two fit across any phone. */
private val MEMBER_WIDTH = 136.dp

/** Narrowest single-column card: one across a phone, two or three on a tablet. */
private val WIDE_MEMBER_WIDTH = 340.dp
