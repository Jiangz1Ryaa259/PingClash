// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/**
 * Latency shows up without a button: opening Proxies tests the whole active profile in the
 * background when its results are missing or old. Tests that time their own measurements turn it
 * off ([PingClashTestRunner]).
 */
internal object ProxyAutoTest {
    @Volatile var enabled = true

    /** A profile is tested again on its own after this long. */
    const val FRESH_MS = 10 * 60 * 1000L

    private val tested = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Whether to test [profile] now: never tested in this run, tested long enough ago, or fewer
     * than half of its nodes have a result (a profile update or new test settings cleared them).
     */
    fun due(profile: LocalProfile, tests: ProxyTestState, now: Long): Boolean {
        if (!enabled || tests.running || profile.nodes.isEmpty()) return false
        val last = tested[profile.id]
        val measured =
            profile.nodes.count { tests.delayFor(profile.id, it.name)?.state in RESULT_STATES }
        return last == null || now - last >= FRESH_MS || measured * 2 < profile.nodes.size
    }

    fun started(profile: LocalProfile, now: Long) {
        tested[profile.id] = now
    }

    private val RESULT_STATES = setOf("passed", "failed", "timeout")
}
