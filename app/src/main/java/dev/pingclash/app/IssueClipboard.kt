// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** Explicit copying of the same public vocabulary shown in the error dialog. */
internal fun copyIssueSummary(context: android.content.Context, notice: IssueNotice): Boolean =
    runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val build =
                tr(R.string.ui_build_identity, info.versionName ?: "?", info.longVersionCode)
            val text =
                listOf(
                        build,
                        notice.title,
                        issueStageTitle(notice.stage),
                        notice.detail,
                        issueAdvice(notice),
                    )
                    .joinToString("\n")
                    .take(MAX_SUMMARY_CHARS)
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            val id = java.util.UUID.randomUUID().toString()
            val clip = android.content.ClipData.newPlainText("PingClash error summary", text)
            clip.description.extras =
                android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                    putString("pingclash.copy", id)
                }
            clipboard.setPrimaryClip(clip)
            android.os
                .Handler(android.os.Looper.getMainLooper())
                .postDelayed(
                    {
                        runCatching {
                            if (
                                clipboard.primaryClipDescription
                                    ?.extras
                                    ?.getString("pingclash.copy") == id
                            )
                                clipboard.clearPrimaryClip()
                        }
                    },
                    SUMMARY_CLIP_MILLIS,
                )
            true
        }
        .getOrDefault(false)

private const val MAX_SUMMARY_CHARS = 12000
private const val SUMMARY_CLIP_MILLIS = 60_000L
