// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

internal enum class ProxySort {
    SUBSCRIPTION,
    NAME,
    LATENCY,
}

// Presentation only: selection, routing order and original group membership
// are never rewritten by sorting or hiding a failed measurement.
internal fun orderedProxyMembers(
    members: List<String>,
    sort: ProxySort,
    hideFailed: Boolean,
    delay: (String) -> ProxyDelay?,
): List<String> {
    val filtered =
        members.filter { name -> !hideFailed || delay(name)?.state !in setOf("failed", "timeout") }
    return when (sort) {
        ProxySort.SUBSCRIPTION -> filtered
        ProxySort.NAME -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        ProxySort.LATENCY ->
            filtered.sortedBy { name ->
                delay(name)?.takeIf { it.state == "passed" }?.elapsedMs ?: Long.MAX_VALUE
            }
    }
}
