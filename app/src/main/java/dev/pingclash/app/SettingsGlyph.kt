// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

private val settingsGlyphs =
    mapOf(
        "live" to PcIcon.Diagnostics,
        "diagnosis" to PcIcon.Diagnostics,
        "usage" to PcIcon.Chart,
        "connections" to PcIcon.Link,
        "network" to PcIcon.Wifi,
        "dns" to PcIcon.Server,
        "apps" to PcIcon.Apps,
        "profiles" to PcIcon.Profile,
        "rules" to PcIcon.Sliders,
        "routing" to PcIcon.Globe,
        "setup" to PcIcon.Check,
        "about" to PcIcon.Info,
        "privacy" to PcIcon.Shield,
        "control" to PcIcon.Power,
        "automation" to PcIcon.Bolt,
        "appearance" to PcIcon.Appearance,
        "groups" to PcIcon.Layers,
        "terminal" to PcIcon.Terminal,
    )

internal fun settingsGlyph(id: String) = settingsGlyphs[id] ?: PcIcon.Gauge
