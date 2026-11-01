// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** Only fixed reason/field labels. Never raw core errors or configuration text. */
internal fun compatibilityWarning(profile: LocalProfile?, group: LocalGroup?): String =
    buildList {
            if (group?.supported == false) add(groupProblem(group.reason))
            if ((group?.unavailable ?: 0) > 0)
                add(
                    trPlural(
                        R.plurals.count_unavailable_member_were_isolated_wit,
                        requireNotNull(group).unavailable,
                        group.unavailable,
                    )
                )
            profile?.let {
                if (it.groupsUnsupported > 0)
                    add(
                        trPlural(
                            R.plurals.count_policy_group_are_unsupported_depende,
                            it.groupsUnsupported,
                            it.groupsUnsupported,
                        )
                    )
                if (it.unsupported > 0) add(rejectionSummary(it.rejections))
                if (it.fieldIssues.isNotEmpty()) add(importFieldSummary(it.fieldIssues))
                if (it.providerRulesSkipped > 0) add(providerRuleWarningText(it))
            }
        }
        .distinct()
        .joinToString("\n\n")

@Composable
internal fun CompatibilityWarningBadge(
    identity: String,
    message: String,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
) {
    val shared = LocalIssueNotices.current
    val center = shared ?: remember { IssueNoticeCenter() }
    val notice =
        IssueNotice(
            "compatibility:$identity:${message.hashCode()}",
            tr(R.string.compatibility_warning),
            message,
            IssueStage.IMPORT,
            IssueSeverity.WARNING,
            "compatibility-warning",
        )
    // AppScreens owns the canonical profile warning. A group adds context only when
    // explicitly opened; otherwise the same import queues two different warning cards.
    LaunchedEffect(identity, message) {
        if (shared == null && message.isNotBlank()) center.publish(notice)
    }
    LaunchedEffect(open, identity, message) {
        if (open && message.isNotBlank()) {
            center.inspect(notice)
            onOpen(false)
        }
    }
    if (shared == null) IssueNoticeHost(center)
}
