// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.util.Locale

/** Where a rule in the running rule table came from. */
internal sealed interface RuleOrigin {
    /** The user's custom rule with this 1-based number. */
    data class Custom(val number: Int) : RuleOrigin

    /** A Service routing choice for this catalogue service. */
    data class Service(val id: String) : RuleOrigin

    data object Blocking : RuleOrigin
}

/**
 * "GEOSITE,youtube" for any spelling of the type and payload, as rules and connections give them.
 */
internal fun ruleKey(type: String, payload: String): String =
    type.trim().uppercase(Locale.ROOT) + "," + payload.trim().lowercase(Locale.ROOT)

/** The key of a rule line ("GEOSITE,YOUTUBE,PROXY"); null when the line has no payload (MATCH). */
internal fun ruleLineKey(line: String): String? {
    val parts = line.split(',')
    return if (parts.size >= RULE_FIELDS) ruleKey(parts[0], parts[1]) else null
}

/** Type, payload and target: a rule line with fewer fields has no payload. */
private const val RULE_FIELDS = 3

/**
 * Explains a connection in terms of the user's own choices. The running table is the custom rules,
 * then Service routing and blocking, then the subscription's rules ([runtimeOptions]); Mihomo takes
 * the first rule that matches, so the first rule with the connection's type and payload is the one
 * it used. Anything not found there came from the subscription.
 */
internal class RouteReasons(private val data: AppData) {
    private val origins: Map<String, RuleOrigin> =
        HashMap<String, RuleOrigin>().apply {
            data.client.customRuleLines().forEachIndexed { index, line ->
                ruleLineKey(line)?.let { putIfAbsent(it, RuleOrigin.Custom(index + 1)) }
            }
            serviceRuleOrigins(
                    data.client.serviceRoutes,
                    data.client.blocking,
                    data.activeProfile?.routingGroups().orEmpty().toSet(),
                )
                .forEach { (line, origin) -> ruleLineKey(line)?.let { putIfAbsent(it, origin) } }
        }

    /** The source of a matched rule, or null when it is the subscription's. */
    fun origin(type: String, payload: String): RuleOrigin? = origins[ruleKey(type, payload)]

    /** One line for the connection list, or null when the core did not report a rule. */
    fun explain(row: ConnectionRow, language: String): String? =
        when {
            data.client.mode == "global" -> tr(R.string.connection_reason_global)
            data.client.mode == "direct" -> tr(R.string.connection_reason_direct)
            row.ruleType.isBlank() -> null
            row.ruleType.equals("MATCH", ignoreCase = true) -> tr(R.string.connection_reason_match)
            else ->
                when (val origin = origin(row.ruleType, row.rulePayload)) {
                    is RuleOrigin.Custom -> tr(R.string.connection_reason_custom, origin.number)
                    is RuleOrigin.Service ->
                        tr(
                            R.string.connection_reason_service,
                            SERVICE_CATALOGUE.firstOrNull { it.id == origin.id }?.label(language)
                                ?: origin.id,
                        )
                    RuleOrigin.Blocking -> tr(R.string.connection_reason_blocking)
                    null ->
                        tr(
                            R.string.connection_reason_subscription,
                            listOf(row.ruleType, row.rulePayload)
                                .filter(String::isNotBlank)
                                .joinToString(","),
                        )
                }
        }
}
