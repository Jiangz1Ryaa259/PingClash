// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** Whitelisted public vocabulary only, never an underlying exception or rule payload. */
internal class VpnConfigurationFailure(
    val code: String,
    val line: Int = 0,
    val ruleType: String = "",
) : IllegalArgumentException(code) {
    fun publicMessage(): String {
        val message =
            fixedMessages[code]?.let { tr(it) }
                ?: when (code) {
                    "rule_unsupported",
                    "rule_metadata_unavailable" ->
                        tr(R.string.runtime_rules_list_item_uses_an_unsupporte, line)
                    "rule_invalid" -> tr(R.string.runtime_rules_list_item_has_an_invalid, line)
                    "rule_geo_unknown" -> tr(R.string.vpn_geo_unknown, line)
                    else -> tr(R.string.vpn_options_invalid)
                }
        return if (line > 0 && code.startsWith("rule_"))
            tr(
                R.string.rule_failure_category,
                ruleType.takeIf { it in publicRuleCategories }
                    ?: tr(R.string.rule_category_unknown),
                message,
            )
        else message
    }

    companion object {
        private const val MAX_RULE_COUNT = 2000

        private val fixedMessages =
            mapOf(
                "dns_invalid" to R.string.vpn_dns_invalid,
                "node_invalid" to R.string.vpn_node_invalid,
                "node_required" to R.string.vpn_node_required,
                "apps_required" to R.string.vpn_apps_required,
                "profile_invalid" to R.string.vpn_profile_invalid,
                "rules_incomplete" to R.string.original_rules_are_invalid_or_exceed_limit,
                "rule_target_limit" to R.string.vpn_rule_target_invalid,
                "rule_provider_invalid" to R.string.vpn_rule_target_invalid,
                "rule_target_invalid" to R.string.vpn_rule_target_invalid,
                "rule_data_unavailable" to R.string.vpn_rule_data_unavailable,
                "rule_limit" to R.string.rules_exceed_the_limit_of_2_000,
            )

        /**
         * Pre-checks a single node. A profile or group target is validated once, completely, by the
         * native executor at start; rebuilding it here with the stricter component graph rejected
         * profiles the executor runs and rebuilt every member of automatic groups.
         */
        /** A node is chosen, and "selected apps only" (saved freely while choosing) has apps. */
        fun requireConnectable(data: AppData) {
            if (data.selected == null) throw VpnConfigurationFailure("node_required")
            if (data.client.appMode == "include" && data.client.packages.isEmpty())
                throw VpnConfigurationFailure("apps_required")
        }

        fun validateNode(config: String) {
            if (isProfileTarget(config)) return
            if (
                org.json
                    .JSONObject(dev.pingclash.mobile.Mobile.validateNode(config))
                    .optString("state") != "passed"
            )
                throw VpnConfigurationFailure("node_invalid")
        }

        private fun isProfileTarget(config: String) =
            runCatching { org.json.JSONObject(config).optString("type") == "profile-target" }
                .getOrDefault(false)

        fun runtimeOptions(data: AppData, validateCore: Boolean = true): String {
            if (
                data.client.mode == "rule" &&
                    data.client.useProviderRules &&
                    (data.activeProfile?.rulesOmitted ?: 0) > 0
            )
                throw VpnConfigurationFailure("rules_incomplete")
            val raw = data.runtimeOptions()
            if (validateCore) {
                val reply =
                    org.json.JSONObject(
                        dev.pingclash.mobile.Mobile.validateRuntimeOptions(
                            activeRuleValidationOptions(raw, data.client.mode)
                        )
                    )
                if (reply.optString("state") != "passed") throw options(reply, raw)
            }
            return raw
        }

        fun checkNativeCode(code: String) {
            val failure = code.removePrefix("vpn_")
            if (
                code.startsWith("vpn_") &&
                    (failure in allowed ||
                        failure in setOf("node_invalid", "dns_invalid", "profile_invalid"))
            )
                throw VpnConfigurationFailure(failure)
        }

        private val allowed =
            setOf(
                "rule_unsupported",
                "rule_metadata_unavailable",
                "rule_invalid",
                "rule_limit",
                "rule_target_limit",
                "rule_provider_invalid",
                "rule_target_invalid",
                "rule_data_unavailable",
                "rule_geo_unknown",
                "options_invalid",
            )

        fun options(reply: org.json.JSONObject, raw: String? = null) =
            VpnConfigurationFailure(
                reply.optString("code").takeIf { it in allowed } ?: "options_invalid",
                reply.optInt("line").coerceIn(0, MAX_RULE_COUNT),
                publicRuleCategory(reply, raw),
            )
    }
}
