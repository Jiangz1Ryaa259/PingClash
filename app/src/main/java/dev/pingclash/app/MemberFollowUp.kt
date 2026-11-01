// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** A completed member is independent of the remaining batch queue. */
internal fun ProxyTestState.memberFinished(
    profile: String,
    name: String,
    group: String = "",
): Boolean =
    !running ||
        kind == "url" &&
            delayFor(profile, name, group)?.state in
                setOf("passed", "failed", "timeout", "busy", "members")
