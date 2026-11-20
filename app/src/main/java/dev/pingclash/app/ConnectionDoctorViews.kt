// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The doctor's card with its live state; off while the app or a diagnosis is busy. */
@Composable
internal fun ConnectionDoctorSection(model: AppViewModel, busy: Boolean) {
    val doctor by model.doctor.state.collectAsStateWithLifecycle()
    val diagnosis by model.diagnosis.collectAsStateWithLifecycle()
    ConnectionDoctorTile(
        doctor,
        enabled = !busy && !diagnosis.running,
        onStart = model.doctor::start,
        onCancel = model.doctor::cancel,
        onNotice = { model.message(it, warning = false) },
    )
}

/** The doctor's card at the top of Connection diagnostics. */
@Composable
internal fun ConnectionDoctorTile(
    state: DoctorState,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onNotice: (String) -> Unit = {},
) {
    Tile {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(PcIcon.Gauge)
            Text(
                tr(R.string.doctor_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        GlassButton(
            onClick = onStart,
            enabled = enabled && !state.running,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("doctor-start"),
            shape = CircleShape,
        ) {
            Text(
                when (state.stage) {
                    DoctorStage.PHONE -> tr(R.string.doctor_checking_phone)
                    DoctorStage.NODE -> tr(R.string.doctor_checking_node)
                    DoctorStage.PEERS -> tr(R.string.doctor_checking_peers)
                    DoctorStage.WEBSITES -> tr(R.string.doctor_checking_websites)
                    DoctorStage.IP -> tr(R.string.doctor_checking_ip)
                    null -> tr(R.string.doctor_start)
                }
            )
        }
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            GlassTextButton(onClick = onCancel, modifier = Modifier.testTag("doctor-cancel")) {
                Text(tr(R.string.cancel))
            }
        }
        state.evidence?.let { DoctorResult(it, onNotice) }
    }
}

@Composable
private fun DoctorResult(evidence: DoctorEvidence, onNotice: (String) -> Unit) {
    val language = LocalUiLanguage.current
    DoctorAnswer(evidence)
    // The IP measured during the run, so a reconnect later does not change this report.
    val exitLine =
        evidence.exit?.let { info ->
            listOf(exitCountryLabel(info.country, language), info.org)
                .filter(String::isNotBlank)
                .joinToString(" · ")
        }
    val lines = doctorLines(evidence, exitLine)
    lines.forEach { DoctorRow(it) }
    HorizontalDivider()
    DoctorDetails(evidence, lines, onNotice)
}

/** A row: its mark, label and value. */
@Composable
private fun DoctorRow(line: DoctorLine) {
    Row(
        Modifier.fillMaxWidth().testTag("doctor-row-${line.tag}").padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkDot(line.mark)
        // The label takes only what it needs; the value gets the rest, on one line at the end.
        Text(tr(line.label), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
        Text(
            line.value,
            Modifier.weight(1f),
            color = Muted,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** The answer: a tinted card with the problem, one thing to do about it, and the code. */
@Composable
private fun DoctorAnswer(evidence: DoctorEvidence) {
    val finding = doctorFinding(evidence)
    val mark = findingMark(finding)
    val color = markColor(mark)
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = .1f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("doctor-answer"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGlyph(
            if (mark == Mark.OK) PcIcon.Check else PcIcon.Warning,
            Modifier.padding(top = 1.dp).size(22.dp),
            color,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                tr(finding.cause, finding.arg),
                Modifier.testTag("doctor-cause"),
                color = color,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            finding.next?.let {
                Text(
                    tr(it, finding.arg),
                    Modifier.testTag("doctor-next"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                listOf(finding.code, evidence.nodeName)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                Modifier.padding(top = 2.dp),
                color = Muted,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun markColor(mark: Mark): Color =
    when (mark) {
        Mark.OK -> Success
        Mark.WARN -> Warning
        Mark.BAD -> MaterialTheme.colorScheme.error
        Mark.SKIP -> Muted.copy(alpha = .5f)
    }

/** A small round mark: green, amber, red, or grey for a check that did not apply. */
@Composable
private fun MarkDot(mark: Mark) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(12.dp).background(markColor(mark), CircleShape)
    )
}
