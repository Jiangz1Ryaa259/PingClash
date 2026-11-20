// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** A connection's matched rule is traced back to the user's own choice that produced it. */
@RunWith(AndroidJUnit4::class)
class RouteReasonsTest {
    private fun row(type: String, payload: String) =
        ConnectionRow(1, "tcp", "PROXY", "", 0, 0, 0, type, payload)

    private fun reasons(mode: String = "rule", custom: String = "") =
        RouteReasons(
            AppData(
                client =
                    ClientSettings(
                        mode = mode,
                        customRules = custom,
                        serviceRoutes = mapOf("youtube" to ROUTE_DIRECT),
                        blocking = BLOCKING_ADS,
                    )
            )
        )

    @Test
    fun eachRuleIsExplainedByWhereItCameFrom() {
        val custom = "DOMAIN-SUFFIX,example.com,DIRECT\n# a comment\n\nIP-CIDR,10.0.0.0/8,DIRECT"
        val reasons = reasons(custom = custom)
        // Numbering follows the rules as they run: comments and blank lines are not rules.
        assertEquals(
            tr(R.string.connection_reason_custom, 1),
            reasons.explain(row("DOMAIN-SUFFIX", "Example.COM"), "en"),
        )
        assertEquals(
            tr(R.string.connection_reason_custom, 2),
            reasons.explain(row("IP-CIDR", "10.0.0.0/8"), "en"),
        )
        assertEquals(
            tr(R.string.connection_reason_service, "YouTube"),
            reasons.explain(row("GEOSITE", "youtube"), "en"),
        )
        assertEquals(
            tr(R.string.connection_reason_blocking),
            reasons.explain(row("GEOSITE", "category-ads-all"), "en"),
        )
        assertEquals(
            tr(R.string.connection_reason_subscription, "GEOSITE,netflix"),
            reasons.explain(row("GEOSITE", "netflix"), "en"),
        )
        assertEquals(tr(R.string.connection_reason_match), reasons.explain(row("MATCH", ""), "en"))
        // Without destinations shown the core reports no rule, so there is nothing to explain.
        assertNull(reasons.explain(row("", ""), "en"))
    }

    @Test
    fun theFirstMatchingRuleWinsAsInMihomo() {
        // A custom rule runs before Service routing, so it is the one that sent the connection.
        val reasons = reasons(custom = "GEOSITE,YOUTUBE,PROXY")
        assertEquals(
            tr(R.string.connection_reason_custom, 1),
            reasons.explain(row("GEOSITE", "youtube"), "en"),
        )
    }

    @Test
    fun globalAndDirectModesExplainEveryConnection() {
        assertEquals(
            tr(R.string.connection_reason_global),
            reasons(mode = "global").explain(row("", ""), "en"),
        )
        assertEquals(
            tr(R.string.connection_reason_direct),
            reasons(mode = "direct").explain(row("GEOSITE", "youtube"), "en"),
        )
    }
}
