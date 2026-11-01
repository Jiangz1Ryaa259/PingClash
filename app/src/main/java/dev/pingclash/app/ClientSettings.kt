// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONArray
import org.json.JSONObject

data class ScenePreferenceChange(
    val scene: String? = null,
    val reduceTransparency: Boolean? = null,
    val solidColor: String? = null,
    val solidSwatches: List<String>? = null,
)

data class ClientSettings(
    // Rule mode by default: a fresh import keeps domestic traffic off the paid node.
    val mode: String = "rule",
    val mtu: Int = 1500,
    val ipv6: Boolean = true,
    val udp: Boolean = true,
    // Printers, NAS, casting and the router page stay reachable while connected.
    val lan: String = "direct",
    val connectTimeout: Int = 10,
    val udpTimeout: Int = 60,
    val maxConnections: Int = 256,
    val dnsMode: String = "tcp",
    val dnsHttps: String = "https://dns.alidns.com/dns-query",
    val appMode: String = "all",
    val packages: List<String> = emptyList(),
    val useProviderRules: Boolean = true,
    val customRules: String = "",
    val inspectConnections: Boolean = false,
    val language: String = "system",
    val theme: String = "system",
    val keepUsage: Boolean = false,
    val showSpeed: Boolean = true,
    val showTraffic: Boolean = true,
    val showMemory: Boolean = false,
    val showService: Boolean = true,
    val notificationSpeed: Boolean = true,
    val probeTimeout: Int = 5000,
    val probeConcurrency: Int = 10,
    val unifiedDelay: Boolean = true,
    val delayUrl: String = "",
    val autoReconnect: Boolean = true,
    val dynamicColor: Boolean = false,
    val reduceEffects: Boolean = false,
    val scene: String = "ocean",
    val reduceTransparency: Boolean = false,
    val useSubscriptionDns: Boolean = true,
    /** #RRGGBB background for the solid ("none") scene. */
    val solidColor: String = DEFAULT_SOLID_COLOR,
    /** The user's palette: presets they kept plus their own colours; never empty. */
    val solidSwatches: List<String> = DEFAULT_SOLID_SWATCHES,
) {
    /** A solid background is designed for the light theme; dark mode is off while it is used. */
    val effectiveTheme
        get() = if (scene == "none") "light" else theme

    fun json(): JSONObject =
        JSONObject().apply {
            put("mode", mode)
            put("mtu", mtu)
            put("ipv6", ipv6)
            put("udp", udp)
            put("lan", lan)
            put("connectTimeout", connectTimeout)
            put("udpTimeout", udpTimeout)
            put("maxConnections", maxConnections)
            put("dnsMode", dnsMode)
            put("dnsHttps", dnsHttps)
            put("appMode", appMode)
            put("packages", JSONArray(packages))
            put("useProviderRules", useProviderRules)
            put("customRules", customRules)
            put("inspectConnections", inspectConnections)
            put("language", language)
            put("theme", theme)
            put("keepUsage", keepUsage)
            put("showSpeed", showSpeed)
            put("showTraffic", showTraffic)
            put("showMemory", showMemory)
            put("showService", showService)
            put("notificationSpeed", notificationSpeed)
            put("probeTimeout", probeTimeout)
            put("probeConcurrency", probeConcurrency)
            put("probeSettingsVersion", 2)
            put("unifiedDelay", unifiedDelay)
            put("delayUrl", delayUrl)
            put("autoReconnect", autoReconnect)
            put("dynamicColor", dynamicColor)
            put("reduceEffects", reduceEffects)
            put("scene", scene)
            put("reduceTransparency", reduceTransparency)
            put("useSubscriptionDns", useSubscriptionDns)
            put("solidColor", solidColor)
            put("solidPaletteVersion", SOLID_PALETTE_VERSION)
            put("solidSwatches", org.json.JSONArray(solidSwatches))
        }

    companion object {
        fun read(p: JSONObject?): ClientSettings {
            if (p == null) return ClientSettings()
            val defaults = ClientSettings()
            val packages = p.optJSONArray("packages") ?: JSONArray()
            require(packages.length() <= 300)
            return ClientSettings(
                p.optString("mode", "rule"),
                p.optInt("mtu", 1500),
                p.optBoolean("ipv6", true),
                p.optBoolean("udp", true),
                p.optString("lan", "direct"),
                p.optInt("connectTimeout", 10),
                p.optInt("udpTimeout", 60),
                p.optInt("maxConnections", 256),
                p.optString("dnsMode", "tcp"),
                p.optString("dnsHttps", defaults.dnsHttps),
                p.optString("appMode", "all"),
                (0 until packages.length()).map(packages::getString),
                p.optBoolean("useProviderRules", true),
                p.optString("customRules"),
                p.optBoolean("inspectConnections"),
                p.optString("language", "system"),
                p.optString("theme", "system"),
                p.optBoolean("keepUsage"),
                p.optBoolean("showSpeed", true),
                p.optBoolean("showTraffic", true),
                p.optBoolean("showMemory", false),
                p.optBoolean("showService", true),
                p.optBoolean("notificationSpeed", true),
                p.optInt("probeTimeout", 5000),
                p.optInt("probeConcurrency", 10).let {
                    if (p.optInt("probeSettingsVersion") < 2 && it == 2) 10 else it
                },
                p.optBoolean("unifiedDelay", true),
                p.optString("delayUrl"),
                p.optBoolean("autoReconnect", true),
                p.optBoolean("dynamicColor"),
                p.optBoolean("reduceEffects"),
                p.optString("scene", "ocean").takeIf { it in setOf("ocean", "clouds", "none") }
                    ?: "ocean",
                p.optBoolean("reduceTransparency"),
                p.optBoolean("useSubscriptionDns", true),
                p.optString("solidColor", DEFAULT_SOLID_COLOR).takeIf(::validHexColor)?.takeUnless {
                    p.optInt("solidPaletteVersion") < SOLID_PALETTE_VERSION &&
                        it.equals(RETIRED_DOVE_COLOR, ignoreCase = true)
                } ?: DEFAULT_SOLID_COLOR,
                p.optJSONArray("solidSwatches")?.let { list ->
                    migrateSolidSwatches(
                        (0 until list.length()).map(list::optString),
                        p.optInt("solidPaletteVersion"),
                    )
                } ?: DEFAULT_SOLID_SWATCHES,
            )
        }
    }
}

data class LocalUsage(
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    val seconds: Long = 0,
    val sessions: Long = 0,
) {
    fun json() =
        JSONObject()
            .put("uploaded", uploaded)
            .put("downloaded", downloaded)
            .put("seconds", seconds)
            .put("sessions", sessions)

    companion object {
        fun read(p: JSONObject?) =
            LocalUsage(
                p?.optLong("uploaded")?.coerceAtLeast(0) ?: 0,
                p?.optLong("downloaded")?.coerceAtLeast(0) ?: 0,
                p?.optLong("seconds")?.coerceAtLeast(0) ?: 0,
                p?.optLong("sessions")?.coerceAtLeast(0) ?: 0,
            )
    }
}

internal fun AppData.runtimeOptions(settings: ClientSettings = client): String {
    // Keep one compiled rule table when switching between rule/global/direct.
    val rules =
        settings.customRules
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList() +
            (if (settings.useProviderRules) activeProfile?.rules.orEmpty() else emptyList())
    if (settings.mode == "rule" && settings.useProviderRules)
        require((activeProfile?.rulesOmitted ?: 0) == 0) {
            tr(R.string.original_rules_are_invalid_or_exceed_limit)
        }
    return JSONObject()
        .put("mode", settings.mode)
        .put("mtu", settings.mtu)
        .put("ipv6", settings.ipv6)
        .put("udp", settings.udp)
        .put("lan", settings.lan)
        .put("connectTimeout", settings.connectTimeout)
        .put("udpTimeout", settings.udpTimeout)
        .put("maxConnections", settings.maxConnections)
        .put("useSubscriptionDNS", settings.useSubscriptionDns)
        .put("dnsMode", settings.dnsMode)
        .put("dnsHttps", settings.dnsHttps)
        .put("inspectConnections", settings.inspectConnections)
        .put("rules", JSONArray(rules))
        .put("definition", activeProfile?.definition.orEmpty())
        .toString()
}

/**
 * An Android package name this app may route: dotted Java-style segments, at most 255 characters,
 * never PingClash itself.
 */
internal fun validPackageName(name: String) =
    name.length <= 255 &&
        name != "dev.pingclash.app" &&
        name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

internal fun validateClientSettings(value: ClientSettings) {
    require(
        value.scene in setOf("ocean", "clouds", "none") &&
            validHexColor(value.solidColor) &&
            value.solidSwatches.size in 1..MAX_SOLID_SWATCHES &&
            value.solidSwatches.all(::validHexColor) &&
            value.solidSwatches.distinct().size == value.solidSwatches.size
    ) {
        tr(R.string.display_or_latency_options_are_invalid_or)
    }
    require(
        value.delayUrl.isEmpty() ||
            value.delayUrl.length <= 2048 &&
                runCatching {
                        val url = java.net.URI(value.delayUrl)
                        url.scheme in listOf("http", "https") &&
                            !url.host.isNullOrBlank() &&
                            url.rawUserInfo == null &&
                            url.rawFragment == null &&
                            (url.port == -1 || url.port in 1..65535)
                    }
                    .getOrDefault(false)
    ) {
        tr(R.string.the_latency_target_must_be_an_http)
    }
    require(
        value.mode in listOf("global", "rule", "direct") &&
            value.mtu in 1280..9000 &&
            value.lan in listOf("proxy", "direct", "block") &&
            value.connectTimeout in 3..60 &&
            value.udpTimeout in 10..300 &&
            value.maxConnections in 16..512 &&
            value.dnsMode in listOf("tcp", "https")
    ) {
        tr(R.string.network_options_are_invalid_or_exceed_limi)
    }
    require(
        value.appMode in listOf("all", "exclude", "include") &&
            value.packages.size <= 300 &&
            value.packages.distinct().size == value.packages.size &&
            value.packages.all(::validPackageName)
    ) {
        tr(R.string.invalid_app_routing_list_include_mode_requ)
    }
    require(
        value.language in listOf("system", "zh", "en") &&
            value.theme in listOf("system", "light", "dark") &&
            value.customRules.toByteArray().size <= 64 * 1024 &&
            value.probeTimeout in 1000..20000 &&
            value.probeConcurrency in 1..16
    ) {
        tr(R.string.display_or_latency_options_are_invalid_or)
    }
}
