// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

enum class IssueStage {
    OPERATION,
    IMPORT,
    PROVIDER,
    DNS,
    TLS,
    NODE,
    RULE,
    VPN,
}

internal enum class IssueSeverity {
    REMINDER,
    WARNING,
}

internal data class IssueNotice(
    val id: String,
    val title: String,
    val detail: String,
    val stage: IssueStage,
    val severity: IssueSeverity,
    val tag: String = "issue-popup",
)

/** Only fixed public copy/counts enter here, never exception/configuration dumps. */
internal class IssueNoticeCenter(
    private val dismissed: MutableState<List<String>> = mutableStateOf(emptyList())
) {
    val entries = mutableStateListOf<IssueNotice>()
    val detail = mutableStateOf<IssueNotice?>(null)

    fun publish(notice: IssueNotice) {
        if (notice.id in dismissed.value || entries.size >= MAX_ENTRIES) return
        if (entries.any { it.id == notice.id || it.sameContent(notice) }) return
        entries.add(notice)
    }

    fun dismiss(id: String) {
        remove(id)
        dismissed.value = (dismissed.value + id).distinct().takeLast(MAX_DISMISSED)
    }

    fun remove(id: String) {
        entries.removeAll { it.id == id }
    }

    fun inspect(notice: IssueNotice) {
        dismiss(notice.id)
        detail.value = notice
    }

    fun current() =
        entries.firstOrNull { it.severity == IssueSeverity.WARNING } ?: entries.firstOrNull()

    private companion object {
        const val MAX_DISMISSED = 128
        const val MAX_ENTRIES = 32
    }
}

private fun IssueNotice.sameContent(other: IssueNotice) =
    stage == other.stage && severity == other.severity && detail == other.detail

internal val LocalIssueNotices = staticCompositionLocalOf<IssueNoticeCenter?> { null }

internal fun issueStageTitle(stage: IssueStage) =
    tr(
        when (stage) {
            IssueStage.OPERATION -> R.string.issue_stage_operation
            IssueStage.IMPORT -> R.string.issue_stage_import
            IssueStage.PROVIDER -> R.string.issue_stage_provider
            IssueStage.DNS -> R.string.issue_stage_dns
            IssueStage.TLS -> R.string.issue_stage_tls
            IssueStage.NODE -> R.string.issue_stage_node
            IssueStage.RULE -> R.string.issue_stage_rule
            IssueStage.VPN -> R.string.issue_stage_vpn
        }
    )

internal fun issueAdvice(stage: IssueStage) =
    tr(
        when (stage) {
            IssueStage.IMPORT -> R.string.issue_advice_import
            IssueStage.PROVIDER -> R.string.issue_advice_provider
            IssueStage.DNS -> R.string.issue_advice_dns
            IssueStage.TLS -> R.string.issue_advice_tls
            IssueStage.NODE -> R.string.issue_advice_node
            IssueStage.RULE -> R.string.issue_advice_rule
            else -> R.string.issue_advice_operation
        }
    )

internal fun issueAdvice(notice: IssueNotice) =
    if (notice.tag == "compatibility-warning") tr(R.string.issue_advice_compatibility)
    else issueAdvice(notice.stage)

internal fun failureStage(failure: Exception) =
    when (failure) {
        is SettingsFailure -> failure.stage
        is ProviderFailure -> IssueStage.PROVIDER
        is SubscriptionTransferFailure -> IssueStage.PROVIDER
        is ProfileImportFailure -> IssueStage.IMPORT
        is java.net.UnknownHostException -> IssueStage.DNS
        is javax.net.ssl.SSLException -> IssueStage.TLS
        is SubscriptionHttpFailure -> IssueStage.PROVIDER
        else -> IssueStage.OPERATION
    }

internal fun vpnIssue(vpn: VpnUiState, id: String) =
    IssueNotice(
        id,
        tr(R.string.ui_connection_failed),
        vpn.message,
        when (vpn.failureCode) {
            "dns_invalid" -> IssueStage.DNS
            "node_invalid",
            "node_required",
            "profile_invalid" -> IssueStage.NODE
            "rules_incomplete",
            "rule_metadata_unavailable",
            "rule_unsupported",
            "rule_invalid",
            "rule_limit",
            "rule_target_limit",
            "rule_provider_invalid",
            "rule_target_invalid",
            "rule_data_unavailable",
            "rule_geo_unknown" -> IssueStage.RULE
            else -> IssueStage.VPN
        },
        IssueSeverity.WARNING,
        "vpn-error-badge",
    )
