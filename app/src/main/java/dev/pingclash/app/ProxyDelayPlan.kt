// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        requested.filter { it in nodes }.forEach { expand(it, context, emptySet()) }
        requested.filterNot { it in nodes }.forEach { expand(it, context, emptySet()) }
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
        return if (name in nodes) recordNode(name, parent) else expandGroup(name, parent, path)
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

internal fun ProxyTestState.delayFor(
    profile: String,
    name: String,
    group: String = "",
): ProxyDelay? {
    val key = delayKey(profile, name, group)
    return values[aliases[key] ?: key]
}

/** Auto-group dependencies can fan out inside the core; never multiply that pool by ten. */
private const val MAX_DELAY_WORKERS = 16

internal suspend fun runDelayPlan(
    plan: ProxyDelayPlan,
    concurrency: Int,
    active: () -> Boolean,
    probe: (String) -> Unit,
) {
    val nodes = plan.independent
    val cursor = AtomicInteger()
    coroutineScope {
        List(minOf(concurrency.coerceIn(1, MAX_DELAY_WORKERS), nodes.size)) {
                async {
                    while (active()) {
                        val index = cursor.getAndIncrement()
                        if (index >= nodes.size) break
                        probe(nodes[index])
                    }
                }
            }
            .awaitAll()
    }
    for (name in plan.chained) {
        if (!active()) break
        probe(name)
    }
}
