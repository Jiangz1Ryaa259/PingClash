// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A test that has not finished: queued, running, or a group's members still running. */
internal val PENDING_STATES = setOf("queued", "running", "members_running")

/** Rows sink a little less than cards under the finger. */
internal const val ROW_PRESS_DEPTH = .98f

/** Everything the page reads to draw groups, members and latencies. */
internal class ProxyPageState(
    val profile: LocalProfile,
    val data: AppData,
    val tests: ProxyTestState,
    private val reported: GroupLive,
    val locked: Boolean,
) {
    val groups = profile.groups.associateBy { it.name }
    val nodes = profile.nodes.associateBy { it.name }

    fun delay(name: String, group: String): ProxyDelay? =
        memberDelay(tests, profile.id, name, group, reported)

    private val stable = HashMap<String, String?>()

    /** The most consistent member of [group] ([mostStable]), worked out once per page state. */
    fun stableIn(group: LocalGroup): String? =
        stable.getOrPut(group.name) {
            mostStable(group.proxies.associateWith { tests.historyFor(profile.id, it, group.name) })
        }

    /** The core's picks, with estimates for automatic groups it has not reported. */
    val live = withEstimates(profile, reported, ::delay)

    /**
     * A member's latency as the route sees it: a node's own; for a group, its batch while one runs,
     * otherwise its exit's, otherwise its batch summary.
     */
    fun reach(name: String, group: String): ProxyDelay? {
        val own = delay(name, group)
        val exit = if (name in groups) profile.exitOf(name, live) else null
        return when {
            name !in groups -> own
            own?.state in PENDING_STATES -> own
            exit != null -> delay(exit, name)
            // No node answered: a failure, never a count of answers.
            own?.state == "members" && own.passed == 0 -> ProxyDelay("failed")
            else -> null
        }
    }

    fun testable(name: String) =
        !tests.running &&
            (name in nodes || name == DIRECT_OUTBOUND || groups[name]?.supported == true)
}

/** What a tap on the page does; the screen supplies the model calls. */
internal class ProxyPageActions(
    val open: (String) -> Unit,
    val choose: (LocalGroup, String) -> Unit,
    val test: (LocalGroup, List<String>) -> Unit,
    val details: (LocalGroup, String) -> Unit,
    val help: (RouteHelp) -> Unit,
)

/**
 * The latency in quiet text, red only when it failed; a tap tests again. Untested shows the test
 * glyph.
 */
@Composable
internal fun LatencyMark(name: String, delay: ProxyDelay?, test: (() -> Unit)?) {
    val tone = latencyTone(delay)
    Row(
        Modifier.widthIn(min = 44.dp)
            .heightIn(min = 32.dp)
            .testTag("proxy-latency-$name")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = test != null && delay != null,
            ) {
                test?.invoke()
            }
            .semantics { contentDescription = tr(R.string.test, name) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        when {
            delay?.state in PENDING_STATES ->
                CircularProgressIndicator(
                    Modifier.size(15.dp).testTag("proxy-pending-$name"),
                    strokeWidth = 1.5.dp,
                )
            delay == null -> Unit
            else ->
                Text(
                    proxyLatencyText(delay),
                    color = latencyColor(tone),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
        }
    }
}

/** The route from the main proxy to the node traffic leaves by. */
@Composable
internal fun RouteCard(state: ProxyPageState, route: ProxyRoute, actions: ProxyPageActions) {
    val exitDelay =
        route.exit?.let { exit ->
            state.delay(exit, route.stops.lastOrNull()?.group?.name.orEmpty())
        }
    val help =
        routeHelp(route, exitDelay) { group, name ->
            state.tests.historyFor(state.profile.id, name, group.name)
        }
    Surface(
        shape = UiCorners.Card,
        color = Panel.copy(alpha = .9f),
        modifier = Modifier.fillMaxWidth().testTag("proxy-route"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            route.stops.forEachIndexed { index, stop ->
                // The exit below names the last stop's choice; it is not repeated here.
                val repeated = index == route.stops.lastIndex && route.exit != null
                RouteStopRow(stop, if (repeated) "" else stop.pick, first = index == 0) {
                    actions.open(stop.group.name)
                }
            }
            RouteExit(route, exitDelay, state, actions)
            help?.let { RouteHelpRow(it, actions) }
        }
    }
}

@Composable
private fun RouteStopRow(stop: RouteStop, choice: String?, first: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag("route-stop-${stop.group.name}")
            .fillMaxWidth()
            .fluidPress(press, ROW_PRESS_DEPTH)
            .clickable(press, null, role = Role.Button, onClick = onClick)
            .height(IntrinsicSize.Min)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteRail(first, exit = false)
        Spacer(Modifier.width(14.dp))
        Text(
            stop.group.name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            choice ?: tr(R.string.proxy_route_automatic),
            Modifier.widthIn(max = 160.dp),
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProxySymbol("right", Modifier.padding(start = 2.dp).size(15.dp), Muted)
    }
}

/** The line down the card: a hollow stop for a group, a filled one for the exit. */
@Composable
private fun RouteRail(first: Boolean, exit: Boolean, tone: LatencyTone = LatencyTone.UNKNOWN) {
    val line = Muted.copy(alpha = .45f)
    // The exit's dot takes its latency colour; grey until it is measured.
    val dot = if (exit) latencyColor(tone) else Muted
    Box(
        Modifier.width(12.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            val stroke = 1.dp.toPx()
            if (!first) drawLine(line, Offset(x, 0f), Offset(x, size.height / 2), stroke)
            if (!exit) drawLine(line, Offset(x, size.height / 2), Offset(x, size.height), stroke)
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (exit) 10.dp else 9.dp)
                .then(
                    if (exit) Modifier.background(dot, CircleShape)
                    else Modifier.background(Panel, CircleShape).border(1.5.dp, dot, CircleShape)
                )
        )
    }
}

@Composable
private fun RouteExit(
    route: ProxyRoute,
    delay: ProxyDelay?,
    state: ProxyPageState,
    actions: ProxyPageActions,
) {
    val exit = route.exit ?: return
    val group = route.stops.lastOrNull()?.group
    Row(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 48.dp)
            .testTag("route-exit"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteRail(first = route.stops.isEmpty(), exit = true, tone = latencyTone(delay))
        Spacer(Modifier.width(14.dp))
        Text(
            exit,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LatencyMark(
            exit,
            delay,
            if (group != null && state.testable(exit)) ({ actions.test(group, listOf(exit)) })
            else null,
        )
    }
}

@Composable
private fun RouteHelpRow(help: RouteHelp, actions: ProxyPageActions) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(
        Modifier.fillMaxWidth().testTag("route-help"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr(R.string.proxy_route_exit_failed),
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        GlassTextButton({ actions.help(help) }, Modifier.testTag("route-help-action")) {
            Text(
                when (help) {
                    is RouteHelp.Switch -> tr(R.string.proxy_route_switch, help.node)
                    RouteHelp.Doctor -> tr(R.string.doctor_start)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A group in the list: what it uses now and how fast that is. */
@Composable
internal fun GroupRow(state: ProxyPageState, group: LocalGroup, actions: ProxyPageActions) {
    val pick = group.current(state.live, state.data.selected?.name)
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag("group-tab-${group.name}")
            .fillMaxWidth()
            .fluidPress(press, ROW_PRESS_DEPTH)
            .clickable(press, null, role = Role.Button) { actions.open(group.name) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The node traffic leaves by, through any nested groups.
            (pick?.let { state.profile.exitOf(it, state.live) ?: it })?.let {
                Text(
                    it,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val delay = pick?.let { state.reach(it, group.name) }
        if (delay != null) LatencyMark("group-${group.name}", delay, null)
        ProxySymbol("right", Modifier.size(15.dp), Muted)
    }
}

/** Latency colours: green for fast, amber for moderate, red for slow or failed. */
@Composable
internal fun latencyColor(tone: LatencyTone): Color =
    when (tone) {
        LatencyTone.GOOD -> LatencyGood
        LatencyTone.SLOW -> LatencySlow
        LatencyTone.BAD -> LatencyBad
        LatencyTone.UNKNOWN -> Muted
    }

@Composable
internal fun ProxyEmpty(profile: LocalProfile?, onProfiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppGlyph(PcIcon.Globe, Modifier.size(44.dp), pageInk().accent)
        Spacer(Modifier.height(20.dp))
        Text(
            if (profile == null) tr(R.string.no_proxy_profiles_yet)
            else tr(R.string.original_policy_groups_are_unavailable_or),
            color = pageInk().text,
            style = MaterialTheme.typography.titleMedium.onPage(),
        )
        Spacer(Modifier.height(12.dp))
        GlassButton(onProfiles) { Text(tr(R.string.add_or_manage_profiles)) }
    }
}
