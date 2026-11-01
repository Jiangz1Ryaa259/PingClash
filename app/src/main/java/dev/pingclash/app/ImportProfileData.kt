// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.mobile.Mobile
import java.util.UUID
import org.json.JSONObject

internal fun parseWithSelections(
    text: String,
    previous: LocalProfile?,
    explicit: Map<String, String>?,
): Pair<JSONObject, Map<String, String>> {
    var parsed = JSONObject(Mobile.importProfile(text))
    val available = readGroups(parsed.optJSONArray("groups")).associateBy { it.name }
    val choices =
        (explicit ?: previous?.userSelections.orEmpty()).filter { (name, choice) ->
            val group = available[name]
            group != null &&
                group.supported &&
                group.type != "load-balance" &&
                (choice in group.proxies || choice.isEmpty() && group.type != "select")
        }
    if (choices.isNotEmpty() && parsed.optString("definition").isNotBlank()) {
        val definition = JSONObject(parsed.getString("definition"))
        val picks = definition.optJSONObject("pingclash-selections") ?: JSONObject()
        choices.forEach { (name, choice) -> picks.put(name, choice) }
        definition.put("pingclash-selections", picks)
        parsed = JSONObject(Mobile.importProfile(definition.toString()))
    }
    return parsed to choices
}

internal fun importedNodes(parsed: JSONObject, previousProfile: LocalProfile?): List<LocalNode> {
    val input = parsed.getJSONArray("nodes")
    return (0 until input.length()).map { index ->
        val node = input.getJSONObject(index)
        val same =
            previousProfile?.nodes?.firstOrNull {
                it.name == node.getString("name") &&
                    it.type == node.getString("type") &&
                    it.config == node.getString("config")
            }
        LocalNode(
            same?.id ?: UUID.randomUUID().toString(),
            node.getString("name"),
            node.getString("type"),
            node.getString("config"),
            node.optBoolean("requiresProfile"),
        )
    }
}
