// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

private const val MAX_DIAGNOSTIC_RULES = 2000
private const val MAX_RUNTIME_CHARS = 3 * 1024 * 1024

/** Public protocol vocabulary, including known native types the bridge cannot execute. */
internal val publicRuleCategories =
    setOf(
        "MATCH",
        "DOMAIN",
        "DOMAIN-SUFFIX",
        "DOMAIN-KEYWORD",
        "DOMAIN-REGEX",
        "DOMAIN-WILDCARD",
        "IP-CIDR",
        "IP-CIDR6",
        "GEOIP",
        "GEOSITE",
        "DST-PORT",
        "NETWORK",
        "AND",
        "OR",
        "NOT",
        "RULE-SET",
        "SRC-GEOIP",
        "IP-ASN",
        "SRC-IP-ASN",
        "SRC-IP-CIDR",
        "IP-SUFFIX",
        "SRC-IP-SUFFIX",
        "SRC-PORT",
        "IN-PORT",
        "DSCP",
        "PROCESS-NAME",
        "PROCESS-PATH",
        "PROCESS-NAME-REGEX",
        "PROCESS-PATH-REGEX",
        "PROCESS-NAME-WILDCARD",
        "PROCESS-PATH-WILDCARD",
        "UID",
        "IN-TYPE",
        "IN-USER",
        "IN-NAME",
        "REMATCH-NAME",
        "SUB-RULE",
    )

internal fun publicRuleCategory(reply: org.json.JSONObject, raw: String?): String {
    val native = reply.optString("ruleType")
    val position = reply.optInt("line")
    // Inspect only the indexed runtime rule, never a JSON physical line or a
    // proxy's cipher. Unknown text and the remainder of the rule stay private.
    return when {
        native in publicRuleCategories -> native
        position !in 1..MAX_DIAGNOSTIC_RULES || raw == null || raw.length > MAX_RUNTIME_CHARS -> ""
        else ->
            runCatching {
                    val item =
                        org.json
                            .JSONObject(raw)
                            .optJSONArray("rules")
                            ?.optString(position - 1)
                            .orEmpty()
                    item
                        .substringBefore(',')
                        .trim()
                        .uppercase(java.util.Locale.ROOT)
                        .takeIf { it in publicRuleCategories }
                        .orEmpty()
                }
                .getOrDefault("")
    }
}

internal fun validateRuntimeSettings(data: AppData, value: ClientSettings) {
    val raw = data.runtimeOptions(value)
    val reply =
        org.json.JSONObject(
            dev.pingclash.mobile.Mobile.validateRuntimeOptions(
                activeRuleValidationOptions(raw, value.mode)
            )
        )
    if (reply.optString("state") != "passed") {
        val failure = VpnConfigurationFailure.options(reply, raw)
        val stage = if (failure.code.startsWith("rule_")) IssueStage.RULE else IssueStage.OPERATION
        throw SettingsFailure(failure.publicMessage(), stage)
    }
}

/** Inactive rules may be staged in global/direct mode; activation in rule mode remains strict. */
internal fun activeRuleValidationOptions(raw: String, mode: String): String =
    if (mode == "rule") raw
    else org.json.JSONObject(raw).put("rules", org.json.JSONArray()).toString()
