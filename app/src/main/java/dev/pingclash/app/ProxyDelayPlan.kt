// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** A measurement plan, never a rewritten subscription or a replacement routing graph. */
internal class ProxyDelayPlan(
    private val profile: LocalProfile,
    requestedNames: List<String>,
    val context: String,
) {
    private val nodes = profile.nodes.associateBy { it.name }
    private val groups = profile.groups.associateBy { it.name }
    private val targets = linkedSetOf<String>()
    private val expanded = mutableMapOf<String, Set<String>>()
    val summaries = linkedMapOf<String, Set<String>>()
    val aliases = linkedMapOf<String, String>()
    private var work = 0

    init {
        val requested = requestedNames.distinct().take(MAX_TARGETS)
        // Directly shown nodes start before nodes discovered inside a group card.
        val leaf = { name: String -> name in nodes || name == DIRECT_OUTBOUND }
        requested.filter(leaf).forEach { expand(it, context, emptySet()) }
        requested.filterNot(leaf).forEach { expand(it, context, emptySet()) }
    }

    val independent
        get() = targets.filter { nodes[it]?.requiresProfile == false }

    val chained
        get() = targets.filter { nodes[it]?.requiresProfile == true }

    val names
        get() = targets.toList()

    fun key(name: String) = delayKey(profile.id, name, context)

    private fun expand(name: String, parent: String, path: Set<String>): Set<String> {
        if (++work > MAX_WORK || path.size > MAX_DEPTH || name in path) return emptySet()
        return if (name in nodes || name == DIRECT_OUTBOUND) recordNode(name, parent)
        else expandGroup(name, parent, path)
    }

    private fun recordNode(name: String, parent: String): Set<String> {
        if (targets.size >= MAX_TARGETS && name !in targets) return emptySet()
        targets.add(name)
        aliases[delayKey(profile.id, name, parent)] = key(name)
        return setOf(name)
    }

    private fun expandGroup(name: String, parent: String, path: Set<String>): Set<String> {
        val group = groups[name] ?: return emptySet()
        val leaves =
            expanded[name]
                ?: if (group.supported) {
                    group.proxies
                        .sortedBy { it !in nodes }
                        .flatMap { expand(it, name, path + name) }
                        .toSet()
                        .also { expanded[name] = it }
                } else emptySet()
        summaries[delayKey(profile.id, name, parent)] = leaves
        return leaves
    }

    fun summaryValues(results: Map<String, ProxyDelay>, running: Boolean): Map<String, ProxyDelay> =
        summaries.mapValues { (_, leaves) ->
            val values = leaves.mapNotNull(results::get)
            val completed = values.count { it.state !in setOf("queued", "running", "idle") }
            ProxyDelay(
                if (running && completed < leaves.size) "members_running" else "members",
                completed = completed,
                total = leaves.size,
                passed = values.count { it.state == "passed" },
            )
        }

    private companion object {
        const val MAX_TARGETS = 500
        const val MAX_WORK = 10000
        const val MAX_DEPTH = 8
    }
}

/** Each finished result of this batch appended to its node's history. */
internal fun ProxyDelayPlan.historyEntries(
    history: Map<String, List<Long>>,
    results: Map<String, ProxyDelay>,
): Map<String, List<Long>> =
    results.entries.associate { (name, delay) ->
        key(name) to history[key(name)].orEmpty().plusResult(delay)
    }

internal fun ProxyTestState.delayFor(
    profile: String,
    name: String,
    group: String = "",
): ProxyDelay? {
    val key = delayKey(profile, name, group)
    return values[aliases[key] ?: key]
}
