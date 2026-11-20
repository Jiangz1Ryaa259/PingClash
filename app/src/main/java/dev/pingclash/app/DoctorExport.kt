// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Everything the page shows, as the text Copy and Download error details give. */
internal fun detailsText(
    evidence: DoctorEvidence,
    lines: List<DoctorLine>,
    facts: List<Pair<String, String>>,
): String {
    val finding = doctorFinding(evidence)
    val block = { rows: List<Pair<String, String>> -> rows.map { (k, v) -> "$k: $v" } }
    return (listOfNotNull(
            tr(finding.cause, finding.arg),
            finding.next?.let { tr(it, finding.arg) },
            "",
        ) +
            block(facts) +
            "" +
            block(lines.map { tr(it.label) to it.value }) +
            (if (evidence.steps.isNullOrEmpty()) emptyList()
            else
                listOf("", tr(R.string.doctor_details_node_check)) +
                    block(stepFacts(evidence.steps))) +
            (if (evidence.peers.isNullOrEmpty()) emptyList()
            else listOf("", tr(R.string.doctor_row_peers)) + block(peerFacts(evidence.peers))))
        .joinToString("\n")
}

/** Writes the details to Downloads as a text file; no permission is needed for the app's own. */
internal fun saveToDownloads(context: Context, content: String): Boolean {
    val name =
        "PingClash-diagnosis-" +
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
            ".txt"
    val values =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            // Hidden from other apps until it is complete.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    val resolver = context.contentResolver
    val uri =
        runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
            .getOrNull() ?: return false
    val written =
        runCatching {
                requireNotNull(resolver.openOutputStream(uri)).use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            .isSuccess
    // A file that could not be written is removed, not left empty in Downloads.
    if (!written) runCatching { resolver.delete(uri, null, null) }
    return written
}
