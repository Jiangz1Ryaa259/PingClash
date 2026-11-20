// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONObject

/** What the running core reports: each group's current member and each node's last check. */
internal data class GroupLive(
    val now: Map<String, String> = emptyMap(),
    /** Milliseconds of the last health check; 0 when it failed. */
    val delays: Map<String, Int> = emptyMap(),
) {
    companion object {
        val EMPTY = GroupLive()

        fun parse(raw: String): GroupLive =
            runCatching {
                    val root = JSONObject(raw)
                    fun strings(key: String) =
                        root.optJSONObject(key)?.let { o ->
                            o.keys().asSequence().associateWith { o.optString(it) }
                        }
                    fun ints(key: String) =
                        root.optJSONObject(key)?.let { o ->
                            o.keys().asSequence().associateWith { o.optInt(it) }
                        }
                    GroupLive(strings("now").orEmpty(), ints("delays").orEmpty())
                }
                .getOrDefault(EMPTY)
    }
}

/** Groups whose member the core chooses unless the user pins one. */
internal val AUTO_GROUP_TYPES = setOf("url-test", "fallback")

internal fun LocalGroup.automatic() = type in AUTO_GROUP_TYPES

/** No member is chosen at all: the core spreads connections over them. */
internal fun LocalGroup.spread() = type == "load-balance"

/** The user's pin in an automatic group, or null while the group chooses for itself. */
internal fun LocalGroup.pin(): String? = selected.takeIf { automatic() && it.isNotEmpty() }

/**
 * The member a group uses now: the user's choice (or the subscription's first member) for a manual
 * group, a pin or the core's live pick for an automatic one, null when unknown (an automatic group
 * while the VPN is off, or load balancing).
 */
internal fun LocalGroup.current(live: GroupLive, mainNode: String? = null): String? =
    when {
        type == "nodes" -> mainNode
        spread() -> null
        automatic() -> pin() ?: live.now[name]
        else -> selected.ifEmpty { proxies.firstOrNull().orEmpty() }.ifEmpty { null }
    }

/** One stop of the route: a group and the member it uses (null when not known). */
internal data class RouteStop(val group: LocalGroup, val pick: String?)

/** Where traffic from the main proxy goes: the groups it passes and the node it leaves by. */
internal data class ProxyRoute(val stops: List<RouteStop>, val exit: String?)

/** Deep enough for any real subscription; also stops a cycle the import let through. */
private const val MAX_ROUTE_DEPTH = 8

/**
 * Follows the main proxy through nested groups to the node it uses. The main proxy is a group, a
 * node, or for a profile without groups a node of the "all proxies" list ([pool]).
 */
/** Mihomo's own direct outbound: a member like a node, measured without a proxy in between. */
internal const val DIRECT_OUTBOUND = "DIRECT"

internal fun proxyRoute(
    data: AppData,
    profile: LocalProfile,
    live: GroupLive,
    pool: LocalGroup?,
): ProxyRoute {
    val main = data.selected
    val groups = profile.groups.associateBy { it.name }
    val start =
        when {
            main == null -> null
            main.type == "group" -> groups[main.name]
            else -> pool
        }
    if (main == null || start == null) return ProxyRoute(emptyList(), main?.name)
    val stops = mutableListOf<RouteStop>()
    var group: LocalGroup? = start
    while (
        group != null && stops.size < MAX_ROUTE_DEPTH && stops.none { it.group.name == group?.name }
    ) {
        val pick = group.current(live, main.name)
        stops += RouteStop(group, pick)
        group = pick?.let(groups::get)
    }
    return ProxyRoute(stops, stops.last().pick?.takeIf { it !in groups })
}

/**
 * The node a member finally leaves by: itself for a node, otherwise followed through its groups.
 * Null when an automatic group's pick is not known yet.
 */
internal fun LocalProfile.exitOf(member: String, live: GroupLive): String? {
    val byName = groups.associateBy { it.name }
    var name: String? = member
    var depth = 0
    while (name != null && name in byName && depth++ < MAX_ROUTE_DEPTH) name =
        byName.getValue(name).current(live)
    return name?.takeIf { it !in byName }
}

/** How a latency reads at a glance. */
internal enum class LatencyTone {
    GOOD,
    SLOW,
    BAD,
    UNKNOWN,
}

/**
 * The user's own test first (in this group, else from a test of the whole profile); while
 * connected, otherwise the core's last health check.
 */
internal fun memberDelay(
    tests: ProxyTestState,
    profileId: String,
    name: String,
    group: String,
    live: GroupLive,
): ProxyDelay? {
    fun usable(delay: ProxyDelay?) = delay?.takeIf { it.state !in setOf("idle", "busy") }
    return usable(tests.delayFor(profileId, name, group))
        ?: usable(tests.delayFor(profileId, name)).takeIf { group.isNotEmpty() }
        ?: live.delays[name]?.let {
            if (it > 0) ProxyDelay("passed", it.toLong()) else ProxyDelay("failed")
        }
}

/**
 * What the core reports, plus, for an automatic group it has not reported (the VPN is off), the
 * node it would pick from the latest results: the fastest for url-test, the first that answered for
 * fallback. So a group always names a node once there are results.
 */
internal fun withEstimates(
    profile: LocalProfile,
    live: GroupLive,
    delay: (String, String) -> ProxyDelay?,
): GroupLive {
    val nodes = profile.nodes.associateBy { it.name }
    val estimates =
        profile.groups
            .filter { it.automatic() && it.pin() == null && it.name !in live.now }
            .mapNotNull { group ->
                val passed =
                    group.proxies
                        .filter { it in nodes }
                        .associateWith { delay(it, group.name) }
                        .filterValues { it?.state == "passed" }
                val pick =
                    if (group.type == "fallback") passed.keys.firstOrNull()
                    else passed.minByOrNull { it.value?.elapsedMs ?: Long.MAX_VALUE }?.key
                pick?.let { group.name to it }
            }
    return if (estimates.isEmpty()) live else live.copy(now = live.now + estimates)
}

internal fun latencyTone(delay: ProxyDelay?): LatencyTone =
    when (delay?.state) {
        "passed" ->
            when (latencyLabel(delay.elapsedMs)) {
                R.string.ui_latency_fast -> LatencyTone.GOOD
                R.string.ui_latency_moderate -> LatencyTone.SLOW
                else -> LatencyTone.BAD
            }
        "failed",
        "timeout",
        "dns",
        "stale" -> LatencyTone.BAD
        else -> LatencyTone.UNKNOWN
    }
