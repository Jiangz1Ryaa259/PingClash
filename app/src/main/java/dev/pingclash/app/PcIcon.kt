// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Stable vector asset IDs, not runtime tuning values.

package dev.pingclash.app

/** Stable vector identity; callers cannot accidentally request an unknown index. */
internal enum class PcIcon(val vectorId: Int) {
    Power(0),
    Globe(1),
    Diagnostics(2),
    Settings(3),
    Add(4),
    ChevronRight(5),
    Shield(6),
    Profile(7),
    Search(8),
    Download(9),
    Terminal(10),
    Gauge(12),
    Tools(13),
    Layers(14),
    Check(15),
    Bolt(16),
    Sliders(17),
    Appearance(18),
    Chart(19),
    Link(20),
    Server(21),
    Wifi(22),
    Lan(23),
    Info(24),
    ChevronDown(25),
    More(26),
    Apps(27),
    Copy(28),
    Sun(29),
    Moon(30),
    Back(31),
    Warning(32),
    Refresh(33),
    Sort(34),
    Grip(35),
    Minus(36),
}
