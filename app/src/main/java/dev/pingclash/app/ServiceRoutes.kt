// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

private const val ROUTE_GROUP = "group:"
private const val GROUP_NAME_LIMIT = 128
private val RESERVED_TARGETS = setOf("PROXY", "DIRECT", "REJECT", "PASS-RULE", "GLOBAL")

/** A route through one of the active profile's groups, stored by name. */
internal fun groupRoute(name: String) = ROUTE_GROUP + name

/** The group a route names, or null for the plain routes. */
internal fun routeGroup(route: String?) =
    route?.takeIf { it.startsWith(ROUTE_GROUP) }?.removePrefix(ROUTE_GROUP)

/**
 * Whether a rule can point at [name]: rule fields are comma-separated, and some words are taken.
 */
internal fun routableGroup(name: String) =
    name.isNotBlank() &&
        name == name.trim() &&
        name.length <= GROUP_NAME_LIMIT &&
        name.none { it == ',' || it.isISOControl() } &&
        name !in RESERVED_TARGETS

internal fun validServiceRoute(route: String) =
    route in ROUTE_VALUES || routeGroup(route)?.let(::routableGroup) == true

/** The groups of this profile a service can be routed through. */
internal fun LocalProfile.routingGroups(): List<String> =
    displayGroups()
        .filter { it.type != "nodes" && it.supported }
        .map { it.name }
        .filter(::routableGroup)

// A group the active profile does not have falls back to the main proxy: choices outlive
// subscriptions, and only a name this profile really has may reach a rule.
private fun routeTarget(route: String?, groups: Set<String>): String? =
    when (route) {
        null -> null
        ROUTE_PROXY -> "PROXY"
        ROUTE_DIRECT -> "DIRECT"
        else -> routeGroup(route)?.let { if (it in groups) it else "PROXY" }
    }

/**
 * Mihomo rules for the user's service choices, ahead of the subscription's rules. Blocking comes
 * first, so an ad host inside a proxied service is still blocked. Narrower services come before the
 * [RoutedService.broad] ones that contain them: YouTube -> Direct still works under Google ->
 * Proxy. A service with no choice adds nothing, so the subscription decides.
 */
internal fun serviceRouteRules(
    routes: Map<String, String>,
    blocking: String,
    groups: Set<String> = emptySet(),
): List<String> {
    val rules = mutableListOf<String>()
    if (blocking == BLOCKING_ADS || blocking == BLOCKING_TELEMETRY)
        rules += "GEOSITE,CATEGORY-ADS-ALL,REJECT"
    if (blocking == BLOCKING_TELEMETRY) rules += "GEOSITE,CATEGORY-ADS-AND-TELEMETRY,REJECT"
    val ordered = SERVICE_CATALOGUE.filterNot { it.broad } + SERVICE_CATALOGUE.filter { it.broad }
    for (service in ordered) {
        val target = routeTarget(routes[service.id], groups) ?: continue
        service.geosite.forEach { rules += "GEOSITE,$it,$target" }
        service.suffixes.forEach { rules += "DOMAIN-SUFFIX,$it,$target" }
        service.full.forEach { rules += "DOMAIN,$it,$target" }
    }
    return rules
}
