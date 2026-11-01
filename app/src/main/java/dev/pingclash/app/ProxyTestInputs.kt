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
