// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** What the route card offers when the exit fails: another node, or the connection doctor. */
internal sealed interface RouteHelp {
    data class Switch(val group: LocalGroup, val node: String) : RouteHelp

    data object Doctor : RouteHelp
}

/**
 * Help only when the exit node fails. In a group the user chooses for, the steadiest other node
 * from recent tests ([mostStable]); with no history to judge, or in an automatic group (which moves
 * on by itself), the connection doctor. Nothing is applied until the user taps it.
 */
internal fun routeHelp(
    route: ProxyRoute,
    exitDelay: ProxyDelay?,
    history: (LocalGroup, String) -> List<Long>,
): RouteHelp? {
    val exit = route.exit
    val last = route.stops.lastOrNull()?.group
    val manual = last != null && !last.automatic() && !last.spread() && last.selectionEditable
    return when {
        exit == null || latencyTone(exitDelay) != LatencyTone.BAD -> null
        last == null || !manual -> RouteHelp.Doctor
        else ->
            steadiestOther(last, exit, history)?.let { RouteHelp.Switch(last, it) }
                ?: RouteHelp.Doctor
    }
}

/** The steadiest member other than the failing exit; a single one with a good record counts. */
private fun steadiestOther(
    group: LocalGroup,
    exit: String,
    history: (LocalGroup, String) -> List<Long>,
): String? {
    val others = group.proxies.filter { it != exit }.associateWith { history(group, it) }
    return mostStable(others)
        ?: others.entries
            .singleOrNull { (_, h) -> stabilityOf(h)?.let { it.failures * 2 < it.checks } == true }
            ?.key
}
