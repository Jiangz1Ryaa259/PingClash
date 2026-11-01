// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONArray
import org.json.JSONObject

data class ProviderRuleWarning(
    val source: Int,
    val entry: Int,
    val type: String,
    val reason: String,
    val count: Int,
) {
    fun json() =
        JSONObject()
            .put("source", source)
            .put("entry", entry)
            .put("type", type)
            .put("reason", reason)
            .put("count", count)
}

internal fun providerSkippedCount(value: JSONObject) =
    value.optInt("providerRulesSkipped").coerceIn(0, MAX_RULE_ENTRIES)

internal fun readProviderRuleWarnings(value: JSONArray?): List<ProviderRuleWarning> {
    if (value == null) return emptyList()
    require(value.length() <= MAX_WARNING_ROWS)
    return (0 until value.length()).map { index ->
        val item = value.getJSONObject(index)
        ProviderRuleWarning(
            item.optInt("source").coerceIn(1, MAX_SOURCES),
            item.optInt("entry").coerceIn(1, MAX_RULE_ENTRIES),
            item.optString("type").takeIf { it in publicRuleCategories } ?: "UNRECOGNIZED",
            item.optString("reason").takeIf { it in warningReasons } ?: "rule_unsupported",
            item.optInt("count").coerceIn(1, MAX_RULE_ENTRIES),
        )
    }
}

internal fun providerRuleWarningText(profile: LocalProfile): String {
    if (profile.providerRulesSkipped <= 0) return ""
    return buildList {
            add(
                trPlural(
                    R.plurals.provider_rules_skipped,
                    profile.providerRulesSkipped,
                    profile.providerRulesSkipped,
                )
            )
            profile.providerRuleWarnings.forEach { item ->
                val kind =
                    if (item.type in publicRuleCategories) item.type
                    else tr(R.string.rule_category_unknown)
                val reason =
                    when (item.reason) {
                        "classical_forbidden" -> R.string.provider_classical_forbidden
                        "rule_metadata_unavailable" -> R.string.provider_rule_metadata_missing
                        "rule_data_unavailable",
                        "rule_geo_unknown" -> R.string.provider_rule_database_missing
                        "rule_invalid",
                        "rule_limit" -> R.string.provider_rule_entry_invalid
                        else -> R.string.provider_rule_entry_unsupported
                    }
                add(
                    trPlural(
                        R.plurals.provider_rule_warning_row,
                        item.count,
                        item.source,
                        item.entry,
                        kind,
                        item.count,
                        tr(reason),
                    )
                )
            }
        }
        .joinToString("\n\n")
}

@Composable
internal fun ProfileCompatibilityNotice(profile: LocalProfile?, center: IssueNoticeCenter) {
    val message = compatibilityWarning(profile, null)
    LaunchedEffect(profile?.id, message) {
        if (message.isNotBlank())
            center.publish(
                IssueNotice(
                    "compatibility:${profile?.id}:${message.hashCode()}",
                    tr(R.string.compatibility_warning),
                    message,
                    if ((profile?.providerRulesSkipped ?: 0) > 0) IssueStage.PROVIDER
                    else IssueStage.IMPORT,
                    IssueSeverity.WARNING,
                    "compatibility-warning",
                )
            )
    }
}

/** An expired subscription is a top-right warning, not red text inside its details. */
@Composable
internal fun SubscriptionExpiryNotice(profile: LocalProfile?, center: IssueNoticeCenter) {
    val expire = profile?.quota?.expire
    val expired = expire != null && expire <= java.time.Instant.now().epochSecond
    val detail = tr(R.string.subscription_expired)
    LaunchedEffect(profile?.id, expire, expired) {
        if (expired)
            center.publish(
                IssueNotice(
                    "subscription-expired:${profile?.id}:$expire",
                    tr(R.string.issue_warning),
                    detail,
                    IssueStage.PROVIDER,
                    IssueSeverity.WARNING,
                    "subscription-expired",
                )
            )
    }
}

/** Cached background failures use the same fixed vocabulary as foreground failures. */
@Composable
internal fun ProviderFetchNotice(profile: LocalProfile, center: IssueNoticeCenter) {
    val record = profile.remote?.error.orEmpty()
    LaunchedEffect(profile.id, record) {
        if (record.isNotBlank()) {
            val parts = record.split(':')
            val failure =
                ProviderFailure(
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1).orEmpty(),
                    parts.getOrNull(2).orEmpty(),
                )
            center.publish(
                IssueNotice(
                    "provider-fetch:${profile.id}:${record.hashCode()}",
                    tr(R.string.issue_warning),
                    failure.publicMessage(),
                    IssueStage.PROVIDER,
                    IssueSeverity.WARNING,
                    "provider-fetch-warning",
                )
            )
        }
    }
}

private val warningReasons =
    setOf(
        "classical_forbidden",
        "rule_metadata_unavailable",
        "rule_data_unavailable",
        "rule_geo_unknown",
        "rule_invalid",
        "rule_limit",
        "rule_unsupported",
    )
private const val MAX_WARNING_ROWS = 64
private const val MAX_SOURCES = 16
private const val MAX_RULE_ENTRIES = 500000
