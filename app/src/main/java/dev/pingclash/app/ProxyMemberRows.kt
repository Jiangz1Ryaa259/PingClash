// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One member in a group's sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemberRow(
    state: ProxyPageState,
    group: LocalGroup,
    name: String,
    actions: ProxyPageActions,
) {
    val chosen = memberChosen(state, group, name)
    val nested = state.groups[name]
    val available =
        name in listOf("DIRECT", "REJECT", "COMPATIBLE") ||
            name in state.nodes ||
            nested?.supported == true
    val selectable = !state.locked && !group.spread() && group.selectionEditable && available
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag("group-choice-${group.name}-$name")
            .semantics { selected = chosen }
            .fillMaxWidth()
            .fluidPress(press, ROW_PRESS_DEPTH)
            .background(if (chosen) LatencyGood.copy(alpha = .12f) else Color.Transparent)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                role = Role.RadioButton,
                // A nested group has no details of its own: its arrow opens its members.
                onLongClickLabel = tr(R.string.proxy_member_details).takeIf { nested == null },
                onLongClick = ({ actions.details(group, name) }).takeIf { nested == null },
                // A nested group that cannot be chosen here opens its members, as its arrow does.
                onClick = {
                    if (selectable) actions.choose(group, name)
                    else if (nested == null) actions.details(group, name)
                    else actions.open(nested.name)
                },
            )
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (available) MaterialTheme.colorScheme.onSurface else Muted,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MemberSubtitle(state, group, name)
        }
        LatencyMark(
            name,
            state.reach(name, group.name),
            if (state.testable(name)) ({ actions.test(group, listOf(name)) }) else null,
        )
        MemberTrailing(chosen, nested, actions)
    }
}

private fun memberChosen(state: ProxyPageState, group: LocalGroup, name: String): Boolean =
    when {
        group.type == "nodes" -> state.data.selected?.name == name
        group.automatic() -> group.pin() == name
        group.spread() -> false
        else -> group.current(state.live) == name
    }

@Composable
private fun MemberSubtitle(state: ProxyPageState, group: LocalGroup, name: String) {
    val nested = state.groups[name]
    val stable = state.nodes[name] != null && state.stableIn(group) == name
    val text =
        listOfNotNull(
                state.nodes[name]?.let { proxyTypeLabel(it.type) },
                // Mihomo's own outbounds are ways to connect, like a node: say which.
                when (name) {
                    "DIRECT" -> tr(R.string.direct)
                    "REJECT" -> tr(R.string.proxy_builtin_reject)
                    else -> null
                },
                nested?.let { state.profile.exitOf(it.name, state.live) ?: it.current(state.live) },
                tr(R.string.proxy_most_stable).takeIf { stable },
                tr(R.string.proxy_route_auto_now).takeIf {
                    group.automatic() && group.pin() == null && state.live.now[group.name] == name
                },
            )
            .joinToString(" · ")
    if (text.isNotEmpty())
        Text(
            text,
            Modifier.then(if (stable) Modifier.testTag("proxy-stable-$name") else Modifier),
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
}

@Composable
private fun MemberTrailing(chosen: Boolean, nested: LocalGroup?, actions: ProxyPageActions) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        when {
            nested != null ->
                androidx.compose.material3.IconButton(
                    { actions.open(nested.name) },
                    Modifier.testTag("proxy-open-${nested.name}").semantics {
                        contentDescription = tr(R.string.open_policy_group, nested.name)
                    },
                ) {
                    ProxySymbol("right", Modifier.size(16.dp), if (chosen) LatencyGood else Muted)
                }
            chosen -> ProxySymbol("check", Modifier.size(18.dp), LatencyGood)
        }
    }
}

/** The first row of an automatic group: let the group choose, which drops the user's pin. */
@Composable
internal fun AutomaticRow(state: ProxyPageState, group: LocalGroup, actions: ProxyPageActions) {
    val chosen = group.pin() == null
    val now = state.live.now[group.name]
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag("group-auto-${group.name}")
            .semantics { selected = chosen }
            .fillMaxWidth()
            .fluidPress(press, ROW_PRESS_DEPTH)
            .background(if (chosen) LatencyGood.copy(alpha = .12f) else Color.Transparent)
            .clickable(
                press,
                null,
                role = Role.RadioButton,
                enabled = !state.locked && group.selectionEditable,
            ) {
                actions.choose(group, "")
            }
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tr(R.string.proxy_route_automatic),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (now != null)
                Text(now, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        now?.let { LatencyMark("auto-${group.name}", state.delay(it, group.name), null) }
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (chosen) ProxySymbol("check", Modifier.size(18.dp), LatencyGood)
        }
    }
    HorizontalDivider(Modifier.height(6.dp).padding(top = 5.dp))
}
