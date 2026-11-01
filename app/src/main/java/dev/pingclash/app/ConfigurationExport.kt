// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject

/** Credential-bearing export, only after the existing explicit export warning. */
internal fun configurationExport(profile: LocalProfile): String {
    val root =
        if (profile.definition.isNotBlank()) JSONObject(profile.definition)
        else JSONObject().put("proxies", JSONArray(profile.nodes.map { JSONObject(it.config) }))
    if (profile.rules.isNotEmpty()) root.put("rules", JSONArray(profile.rules))
    return Mobile.exportConfiguration(root.toString()).also { require(it.isNotEmpty()) }
}
