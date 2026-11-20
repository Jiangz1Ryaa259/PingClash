// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import kotlin.math.abs

/** Recent URL test results kept per node; older ones are dropped. */
internal const val STABILITY_HISTORY = 10

/** Fewer checks than this say nothing about consistency. */
internal const val STABILITY_MIN_CHECKS = 3

/** A failed or timed-out check in the history. */
internal const val FAILED_CHECK = -1L

/**
 * How consistent a node was over its recent checks: how many failed, its typical (median) latency
 * and how far checks usually were from it (mean absolute deviation), in milliseconds.
 */
internal data class NodeStability(
    val checks: Int,
    val failures: Int,
    val typicalMs: Long,
    val spreadMs: Long,
)

/** Null until the node has [STABILITY_MIN_CHECKS] checks. */
internal fun stabilityOf(history: List<Long>): NodeStability? {
    if (history.size < STABILITY_MIN_CHECKS) return null
    val passed = history.filter { it >= 0 }.sorted()
    val typical = if (passed.isEmpty()) 0L else passed[passed.size / 2]
    val spread = if (passed.isEmpty()) 0L else passed.sumOf { abs(it - typical) } / passed.size
    return NodeStability(history.size, history.size - passed.size, typical, spread)
}

/**
 * The most stable of several rated nodes: the lowest share of failures, then the smallest spread,
 * then the lower typical latency. Null unless at least two nodes are rated (one node is not a
 * choice) and the best one passed most of its checks.
 */
internal fun mostStable(histories: Map<String, List<Long>>): String? {
    val rated = histories.mapNotNull { (name, history) -> stabilityOf(history)?.let { name to it } }
    if (rated.size < 2) return null
    val best =
        rated.minWith(
            compareBy<Pair<String, NodeStability>> {
                    it.second.failures.toDouble() / it.second.checks
                }
                .thenBy { it.second.spreadMs }
                .thenBy { it.second.typicalMs }
        )
    return best.first.takeIf { best.second.failures * 2 < best.second.checks }
}

/** One result appended to a node's history; states that are not a result leave it unchanged. */
internal fun List<Long>.plusResult(delay: ProxyDelay): List<Long> =
    when (delay.state) {
        "passed" -> (this + delay.elapsedMs.coerceAtLeast(1)).takeLast(STABILITY_HISTORY)
        "failed",
        "timeout" -> (this + FAILED_CHECK).takeLast(STABILITY_HISTORY)
        else -> this
    }

internal fun ProxyTestState.historyFor(
    profile: String,
    name: String,
    group: String = "",
): List<Long> {
    val key = delayKey(profile, name, group)
    // A test of the whole profile records under no group; it counts for every group.
    return history[aliases[key] ?: key] ?: history[delayKey(profile, name)].orEmpty()
}

/** The details sheet's line: checks, failures, typical latency and spread. */
internal fun stabilityText(stability: NodeStability?): String =
    when {
        stability == null -> tr(R.string.proxy_stability_pending, STABILITY_MIN_CHECKS)
        stability.failures == stability.checks ->
            tr(R.string.proxy_stability_all_failed, stability.checks)
        else ->
            tr(
                R.string.proxy_stability_value,
                stability.checks,
                stability.failures,
                stability.typicalMs,
                stability.spreadMs,
            )
    }
