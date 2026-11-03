// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONObject

/** Compare batch inputs without treating local member selection as a configuration edit. */
internal class ProxyTestInputs(data: AppData) {
    private val profile = data.activeProfile
    private val id = profile?.id
    private val nodes = profile?.nodes?.map { it.name to it.config }
    private val definition =
        profile?.definition?.takeIf(String::isNotBlank)?.let {
            JSONObject(it).apply { remove("pingclash-selections") }.toString()
        }
    private val settings =
        data.client.let {
            listOf(it.probeTimeout, it.probeConcurrency, it.delayUrl, it.unifiedDelay)
        }
    private val dns = data.dnsAddress
    private val target = data.probeUrl

    override fun equals(other: Any?) =
        other is ProxyTestInputs &&
            id == other.id &&
            nodes == other.nodes &&
            definition == other.definition &&
            settings == other.settings &&
            dns == other.dns &&
            target == other.target

    override fun hashCode() = listOf(id, nodes, definition, settings, dns, target).hashCode()

    override fun toString() = "ProxyTestInputs(redacted)"
}

/**
 * Whether a finished latency result ([delayKey]) still describes its node after the data changed
 * from [before] to [after]: the profile still exists, a node keeps its configuration, and the test
 * settings are the same. Group results stay with their profile.
 */
internal fun delayStillValid(key: String, before: AppData, after: AppData): Boolean {
    fun settings(data: AppData) =
        listOf(
            data.client.probeTimeout,
            data.client.delayUrl,
            data.client.unifiedDelay,
            data.dnsAddress,
            data.probeUrl,
        )
    if (settings(before) != settings(after)) return false
    val parts = key.split('\u0000')
    val old = before.profiles.firstOrNull { it.id == parts.first() }
    val new = after.profiles.firstOrNull { it.id == parts.first() }
    val name = parts.last()
    fun config(profile: LocalProfile?) = profile?.nodes?.firstOrNull { it.name == name }?.config
    return new != null && config(old) == config(new)
}
