// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

enum class VpnPhase(val wire: String, val active: Boolean) {
    IDLE("idle", false),
    CONNECTING("connecting", true),
    ESTABLISHED("established", true),
    STOPPING("stopping", true),
    ERROR("error", false);

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: ERROR
    }
}
