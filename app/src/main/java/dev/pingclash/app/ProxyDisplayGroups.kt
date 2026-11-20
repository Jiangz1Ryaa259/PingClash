// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONObject

// A tab's members come only from that tab. Never append the profile's node pool
// or flatten nested groups into their parent. A groupless profile gets one pool.
// Groups the subscription marks `hidden` are left out; if it hides every one, all are shown, so
// the page never says "no proxies" for a profile that has them.
internal fun LocalProfile.displayGroups(
    hidden: Set<String> = hiddenGroupNames()
): List<LocalGroup> {
    if (groups.isEmpty())
        return if (hasSubscriptionGroups) emptyList()
        else listOf(LocalGroup(tr(R.string.all_proxies), "nodes", nodes.map { it.name }, "", true))
    return groups.filter { it.name !in hidden }.ifEmpty { groups }
}

/** The names of the groups the profile's definition marks `hidden`: a parse of the whole JSON. */
internal fun LocalProfile.hiddenGroupNames(): Set<String> =
    runCatching {
            val raw = JSONObject(definition).optJSONArray("proxy-groups")
            if (raw == null) emptySet()
            else
                (0 until raw.length())
                    .mapNotNull {
                        raw.optJSONObject(it)?.takeIf { it.optBoolean("hidden") }?.optString("name")
                    }
                    .toSet()
        }
        .getOrDefault(emptySet())
