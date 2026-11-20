// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.pingclash.diagnostics.Models
import dev.pingclash.mobile.Mobile
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun supportFacts(context: Context, evidence: DoctorEvidence, mode: String): SupportFacts {
    val version =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    val core =
        runCatching { org.json.JSONObject(Mobile.info()).optString("engineVersion") }
            .getOrDefault("")
    val phone = evidence.phone
    return SupportFacts(
        app = "PingClash $version",
        core = core.ifBlank { "—" },
        time = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")),
        android =
            listOfNotNull("Android ${Build.VERSION.RELEASE}", phone?.maker?.ifBlank { null })
                .joinToString(" · "),
        network = phone?.let { linkLabel(it.link) } ?: "—",
        mode = mode,
        protocol = evidence.protocol.ifBlank { "—" },
    )
}

/** Country and, when location is allowed, the approximate province. */
internal fun regionLabel(region: RegionInfo?): String =
    listOfNotNull(
            region?.country,
            region?.province?.let { tr(R.string.doctor_region_approx, tr(it.label)) },
        )
        .joinToString(" · ")
        .ifBlank { tr(R.string.doctor_region_unknown) }

/** Every fact of the details, label to value, in the order shown and copied. */
internal fun detailFacts(
    evidence: DoctorEvidence,
    facts: SupportFacts,
): List<Pair<String, String>> {
    val region = evidence.region
    return listOfNotNull(
        tr(R.string.doctor_details_code) to doctorSupportCode(evidence),
        evidence.group.ifBlank { null }?.let { tr(R.string.doctor_details_group) to it },
        tr(R.string.doctor_support_node) to evidence.nodeName.ifBlank { "—" },
        tr(R.string.doctor_support_protocol) to facts.protocol,
        tr(R.string.doctor_support_mode) to facts.mode,
        tr(R.string.doctor_support_network) to facts.network,
        tr(R.string.doctor_details_carrier) to
            (region?.carrier ?: tr(R.string.doctor_region_unknown)),
        tr(R.string.doctor_details_region) to regionLabel(region),
        tr(R.string.doctor_support_app) to facts.app,
        tr(R.string.doctor_details_core) to facts.core,
        tr(R.string.doctor_support_android) to facts.android,
        tr(R.string.doctor_support_time) to facts.time,
    )
}

/** The node check's steps: stage, then the code (which says how it went) and time. */
internal fun stepFacts(steps: List<Models.Step>?): List<Pair<String, String>> =
    steps.orEmpty().map { it.stage.title() to "${it.code.name} · ${it.elapsedMs} ms" }

/** The other nodes: name, then its time or the core's code. */
internal fun peerFacts(peers: List<PeerResult>?): List<Pair<String, String>> =
    peers.orEmpty().map { it.name to if (it.ok) "OK · ${it.ms} ms" else it.code.uppercase() }

/** A row of the details: a label and its value, or a group title across both columns. */
private sealed interface DetailRow {
    data class Fact(val label: String, val value: String) : DetailRow

    data class Title(val text: String) : DetailRow
}

/** Error details under the result: every fact, the steps and the other nodes, then the buttons. */
@Composable
internal fun DoctorDetails(
    evidence: DoctorEvidence,
    lines: List<DoctorLine>,
    notify: (String) -> Unit,
) {
    val context = LocalContext.current
    val mode = modeLabel(evidence.mode)
    // Everything here was read during the run, so a copy or a download matches what is shown.
    val support = remember(evidence, mode) { supportFacts(context, evidence, mode) }
    val facts = detailFacts(evidence, support)
    val rows = buildList {
        facts.forEach { add(DetailRow.Fact(it.first, it.second)) }
        listOf(
                R.string.doctor_details_node_check to stepFacts(evidence.steps),
                R.string.doctor_row_peers to peerFacts(evidence.peers),
            )
            .filter { it.second.isNotEmpty() }
            .forEach { (title, group) ->
                add(DetailRow.Title(tr(title)))
                group.forEach { add(DetailRow.Fact(it.first, it.second)) }
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr(R.string.doctor_details_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        DetailGrid(rows, Modifier.testTag("doctor-details"))
        DetailButtons({ detailsText(evidence, lines, facts) }, notify)
    }
}

/**
 * Each section's labels in one column as wide as its widest label (at most two fifths of the width;
 * a longer one, such as a node's name, is cut short), its values in the next. A long node name in
 * Other nodes does not widen the facts above it.
 */
@Composable
private fun DetailGrid(rows: List<DetailRow>, modifier: Modifier = Modifier) {
    Layout(modifier = modifier, content = { DetailCells(rows) }) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val rowGap = 6.dp.roundToPx()
        val titleGap = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val labelWidths = labelColumns(rows, measurables, width)
        var index = 0
        val placed = mutableListOf<Triple<Placeable, Int, Int>>()
        var y = 0
        rows.forEachIndexed { i, row ->
            if (i > 0) y += if (row is DetailRow.Title) titleGap else rowGap
            when (row) {
                is DetailRow.Title -> {
                    val title = measurables[index++].measure(Constraints(maxWidth = width))
                    placed += Triple(title, 0, y)
                    y += title.height
                }
                is DetailRow.Fact -> {
                    val labelWidth = labelWidths[i]
                    val valueWidth = (width - labelWidth - gap).coerceAtLeast(0)
                    val label = measurables[index++].measure(Constraints.fixedWidth(labelWidth))
                    val value = measurables[index++].measure(Constraints(maxWidth = valueWidth))
                    // Label and value share the first line's baseline, whatever the script.
                    val baseline = maxOf(label[FirstBaseline], value[FirstBaseline])
                    val labelTop = y + baseline - label[FirstBaseline]
                    val valueTop = y + baseline - value[FirstBaseline]
                    placed += Triple(label, 0, labelTop)
                    placed += Triple(value, labelWidth + gap, valueTop)
                    y = maxOf(labelTop + label.height, valueTop + value.height)
                }
            }
        }
        layout(width, y) { placed.forEach { (p, x, top) -> p.place(x, top) } }
    }
}

/** A title, or a label and its value, for each row, in order. */
@Composable
private fun DetailCells(rows: List<DetailRow>) {
    val small = MaterialTheme.typography.bodySmall
    rows.forEach { row ->
        when (row) {
            is DetailRow.Title ->
                Text(
                    row.text,
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            is DetailRow.Fact -> {
                Text(
                    row.label,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = small,
                )
                Text(row.value, style = small)
            }
        }
    }
}

/**
 * For each row, the label width of its section (the rows between two titles): the section's widest
 * label, but never more than [LABEL_SHARE] of the width.
 */
private fun labelColumns(
    rows: List<DetailRow>,
    measurables: List<Measurable>,
    width: Int,
): IntArray {
    val widths = IntArray(rows.size)
    val cap = (width * LABEL_SHARE).toInt()
    var index = 0
    var start = 0
    var widest = 0
    fun close(end: Int) {
        for (row in start until end) widths[row] = widest.coerceAtMost(cap)
    }
    rows.forEachIndexed { i, row ->
        when (row) {
            is DetailRow.Title -> {
                close(i)
                start = i + 1
                widest = 0
                index++
            }
            is DetailRow.Fact -> {
                widest = maxOf(widest, measurables[index].maxIntrinsicWidth(Int.MAX_VALUE))
                index += 2
            }
        }
    }
    close(rows.size)
    return widths
}

private const val LABEL_SHARE = .4f

/** Copy, and Download error details; each says when it is done. */
@Composable
private fun DetailButtons(text: () -> String, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A second tap while the file is written would only make a second file.
    var saving by remember { mutableStateOf(false) }
    // One above the other at full width, so neither label wraps in any language.
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassButton(
            onClick = {
                context
                    .getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("PingClash", text()))
                notify(tr(R.string.doctor_support_copied))
            },
            modifier = Modifier.fillMaxWidth().testTag("doctor-copy"),
        ) {
            Text(tr(R.string.doctor_copy), maxLines = 1)
        }
        GlassButton(
            onClick = {
                if (saving) return@GlassButton
                saving = true
                val content = text()
                scope.launch {
                    try {
                        val saved =
                            withContext(Dispatchers.IO) { saveToDownloads(context, content) }
                        notify(
                            tr(if (saved) R.string.doctor_saved else R.string.doctor_save_failed)
                        )
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().testTag("doctor-download"),
        ) {
            Text(tr(R.string.doctor_download), maxLines = 1)
        }
    }
}
