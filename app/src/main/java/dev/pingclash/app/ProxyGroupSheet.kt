// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Longest search text, as on other pages. */
private const val MAX_QUERY = 100

/** How members are listed (kept across sheets) and what the sheet's menu can do. */
internal class GroupSheetOptions(
    val sort: MutableState<ProxySort>,
    val hideFailed: MutableState<Boolean>,
    /** Kept by the page, so the list returns where it was after a trip to the diagnosis. */
    val list: LazyListState,
    val close: SheetClose,
    val tcpTest: (LocalGroup) -> Unit,
    val makeMain: (LocalGroup) -> Unit,
)

/** Both close this sheet alone, which shows the group it was opened from, if any. */
internal class SheetClose(val back: () -> Unit, val dismiss: () -> Unit)

/** A group's members: nodes and nested groups as the subscription lists them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSheet(
    state: ProxyPageState,
    group: LocalGroup,
    actions: ProxyPageActions,
    options: GroupSheetOptions,
) {
    var query by rememberSaveable(group.name) { mutableStateOf("") }
    var searching by rememberSaveable(group.name) { mutableStateOf(false) }
    // Closing a sheet, by back, a swipe or a tap outside, shows the one it was opened from.
    GlassSheet(
        onDismissRequest = options.close.dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("group-sheet"),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler(onBack = options.close.back)
        Column {
            GroupSheetHeader(state, group, actions, options) {
                searching = !searching
                if (!searching) query = ""
            }
            if (searching)
                GlassField(
                    query,
                    { if (it.length <= MAX_QUERY) query = it },
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                        .testTag("proxy-search"),
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = { Text(tr(R.string.search_this_group)) },
                )
            if (!group.supported)
                Text(
                    groupProblem(group.reason),
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            GroupMembers(state, group, actions, options, query)
        }
    }
}

/** The members, sorted and filtered as chosen, with an automatic group's own row first. */
@Composable
private fun GroupMembers(
    state: ProxyPageState,
    group: LocalGroup,
    actions: ProxyPageActions,
    options: GroupSheetOptions,
    query: String,
) {
    val members =
        orderedProxyMembers(
            group.proxies.filter { query.isBlank() || it.contains(query, true) },
            options.sort.value,
            options.hideFailed.value,
        ) {
            state.reach(it, group.name)
        }
    LazyColumn(
        Modifier.fillMaxWidth().testTag("group-members-${group.name}"),
        state = options.list,
    ) {
        if (group.automatic() && query.isBlank()) item { AutomaticRow(state, group, actions) }
        items(members, key = { it }) { name ->
            MemberRow(state, group, name, actions)
            HorizontalDivider(Modifier.padding(start = 20.dp))
        }
        if (members.isEmpty())
            item {
                Text(
                    tr(R.string.no_matching_members_in_this_group),
                    Modifier.padding(20.dp),
                    color = Muted,
                )
            }
    }
}

@Composable
private fun GroupSheetHeader(
    state: ProxyPageState,
    group: LocalGroup,
    actions: ProxyPageActions,
    options: GroupSheetOptions,
    toggleSearch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            toggleSearch,
            Modifier.semantics { contentDescription = tr(R.string.search_proxies) },
        ) {
            AppGlyph(PcIcon.Search, Modifier.size(22.dp))
        }
        GroupTestButton(state, group, actions)
        GroupSheetMenu(state, group, options)
    }
}

@Composable
private fun GroupTestButton(state: ProxyPageState, group: LocalGroup, actions: ProxyPageActions) {
    val running = state.tests.running
    IconButton(
        { actions.test(group, if (running) emptyList() else group.proxies) },
        // As the route list counts them: DIRECT has a latency too. Not while the page is locked.
        enabled =
            running ||
                !state.locked &&
                    group.proxies.any {
                        it in state.nodes ||
                            it == DIRECT_OUTBOUND ||
                            state.groups[it]?.supported == true
                    },
        modifier =
            Modifier.testTag("proxy-test-group").semantics {
                contentDescription =
                    if (running) tr(R.string.cancel_tests) else tr(R.string.test_current_group)
            },
    ) {
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else ProxySymbol("bolt")
    }
}

@Composable
private fun GroupSheetMenu(state: ProxyPageState, group: LocalGroup, options: GroupSheetOptions) {
    var open by remember { mutableStateOf(false) }
    Box {
        MenuIconButton(
            { open = true },
            Modifier.testTag("group-menu").semantics {
                contentDescription = tr(R.string.proxy_options)
            },
        ) {
            ProxySymbol("menu")
        }
        GlassMenu(open, { open = false }) {
            ProxySort.entries.forEach { value ->
                GlassMenuItem(
                    text = { Text(tr(sortLabel(value))) },
                    leadingIcon = { RadioButton(options.sort.value == value, null) },
                    onClick = {
                        options.sort.value = value
                        open = false
                    },
                )
            }
            GlassMenuItem(
                text = { Text(tr(R.string.proxy_hide_failed)) },
                leadingIcon = { Checkbox(options.hideFailed.value, null) },
                onClick = {
                    options.hideFailed.value = !options.hideFailed.value
                    open = false
                },
            )
            GlassMenuItem(
                { Text(tr(R.string.latency_tcp_test)) },
                {
                    open = false
                    options.tcpTest(group)
                },
                enabled = !state.tests.running && !state.locked,
            )
            val main = state.data.selectedId == state.profile.groupId(group.name)
            GlassMenuItem(
                {
                    Text(
                        tr(
                            if (main) R.string.current_group_is_already_the_main_proxy
                            else R.string.use_current_group_as_main_proxy
                        )
                    )
                },
                {
                    open = false
                    options.makeMain(group)
                },
                enabled = !state.locked && !main && group.supported && group.type != "nodes",
                modifier = Modifier.testTag("group-main-${group.name}"),
            )
        }
    }
}

private fun sortLabel(sort: ProxySort) =
    when (sort) {
        ProxySort.SUBSCRIPTION -> R.string.proxy_sort_subscription
        ProxySort.NAME -> R.string.proxy_sort_name
        ProxySort.LATENCY -> R.string.proxy_sort_latency
    }
