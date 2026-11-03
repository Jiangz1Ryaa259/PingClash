// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

internal val deferredSettingFields = setOf("dnsHttps", "delayUrl", "customRules")
internal val liveAppearanceFields =
    setOf(
        "language",
        "theme",
        "dynamicColor",
        "reduceEffects",
        "scene",
        "reduceTransparency",
        "showSpeed",
        "showTraffic",
        "showMemory",
        "showService",
        "notificationSpeed",
        "keepUsage",
        "startOnBoot",
        "subscriptionHours",
        "wifiAuto",
        "trustedWifi",
    )

/** Apply only the edited keys, preserving concurrent appearance/local edits. */
internal fun mergeSettingsDelta(
    before: ClientSettings,
    after: ClientSettings,
    onto: ClientSettings,
    exclude: Set<String> = emptySet(),
): ClientSettings {
    val old = before.json()
    val next = after.json()
    val merged = onto.json()
    next
        .keys()
        .asSequence()
        .filter { it !in exclude && old.opt(it)?.toString() != next.opt(it)?.toString() }
        .forEach { merged.put(it, next.get(it)) }
    return ClientSettings.read(merged)
}

internal fun changedSettingFields(before: ClientSettings, after: ClientSettings): Set<String> {
    val old = before.json()
    val next = after.json()
    return next
        .keys()
        .asSequence()
        .filter { old.opt(it)?.toString() != next.opt(it)?.toString() }
        .toSet()
}
