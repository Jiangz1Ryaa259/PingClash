// SPDX-License-Identifier: GPL-3.0-only
// Minimum targets and panel spacing are shared interface design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pingclash.diagnostics.Models
import dev.pingclash.diagnostics.ReportFormatter

@Composable
internal fun AuxiliaryDiagnosticScreen(model: DiagnosticViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by model.state.collectAsStateWithLifecycle()
    DiagnosticContextNotice(state.report?.runId ?: "auxiliary", state.steps, state.running)
    var preview by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown")
        ) { uri ->
            if (uri != null) ReportExporter.save(context, uri, pending)
        }
    Column(
        Modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .readableWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = LocalNavigationClearance.current),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onBack,
                Modifier.testTag("auxiliary-back").semantics {
                    contentDescription = tr(R.string.back)
                },
            ) {
                AppGlyph(BACK_GLYPH)
            }
            PageHeading(tr(R.string.direct_auxiliary_checks), tag = "auxiliary-title")
        }
        AuxiliaryProbeForm(state.running, model::start, model::cancel)
        AuxiliaryEvidence(state.steps)
        state.report?.let { report ->
            SettingsCard {
                Text(tr(R.string.result), style = MaterialTheme.typography.titleMedium)
                DiagnosticConclusion(report)
                Text("${report.networkType} · ${report.elapsedMs} ms", color = Muted)
                OutlinedButton({ preview = true }) { Text(tr(R.string.preview_redacted_report)) }
            }
        }
    }
    if (preview)
        state.report?.let { report ->
            ReportPreview(
                report,
                onSave = {
                    pending = ReportFormatter.markdown(it)
                    save.launch("PingClash-${it.runId.take(8)}.md")
                },
            ) {
                preview = false
            }
        }
}

private data class AuxiliaryInput(
    val host: String = "",
    val port: String = "443",
    val sni: String = "",
    val tls: Boolean = true,
    val family: Models.Family = Models.Family.AUTO,
)

@Composable
private fun AuxiliaryProbeForm(
    running: Boolean,
    onStart: (Models.Request) -> Unit,
    onCancel: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var sni by remember { mutableStateOf("") }
    var tls by rememberSaveable { mutableStateOf(true) }
    var family by rememberSaveable { mutableStateOf(Models.Family.AUTO) }
    val input = AuxiliaryInput(host, portText, sni, tls, family)
    var error by remember { mutableStateOf<String?>(null) }
    SettingsCard {
        AuxiliaryAddressFields(input, !running) {
            host = it.host
            portText = it.port
            sni = it.sni
            tls = it.tls
            error = null
        }
        AuxiliaryFamilyChoice(input.family, !running) { family = it }
        error?.let { InputIssue(it) }
        Button(
            {
                try {
                    val port =
                        input.port.toIntOrNull()
                            ?: throw IllegalArgumentException(tr(R.string.enter_a_valid_port))
                    val request =
                        Models.Request(input.host, port, input.tls, input.sni, input.family)
                    error = null
                    onStart(request)
                } catch (invalid: IllegalArgumentException) {
                    error = invalid.message
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                tr(
                    if (running) R.string.collecting_connection_evidence
                    else R.string.start_diagnostics
                )
            )
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onCancel) { Text(tr(R.string.cancel_diagnostics)) }
        }
    }
}

@Composable
private fun AuxiliaryFamilyChoice(
    selected: Models.Family,
    enabled: Boolean,
    onChange: (Models.Family) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
                Models.Family.AUTO to tr(R.string.automatic),
                Models.Family.IPV4 to "IPv4",
                Models.Family.IPV6 to "IPv6",
            )
            .forEach { (option, label) ->
                FilterChip(
                    selected == option,
                    { onChange(option) },
                    label = { Text(label) },
                    enabled = enabled,
                )
            }
    }
}

@Composable
private fun AuxiliaryAddressFields(
    input: AuxiliaryInput,
    enabled: Boolean,
    onChange: (AuxiliaryInput) -> Unit,
) {
    OutlinedTextField(
        input.host,
        { onChange(input.copy(host = it)) },
        Modifier.fillMaxWidth().testTag("auxiliary-host"),
        enabled = enabled,
        singleLine = true,
        label = { Text(tr(R.string.server_domain_ip)) },
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            input.port,
            { if (it.length <= 5 && it.all(Char::isDigit)) onChange(input.copy(port = it)) },
            Modifier.width(110.dp),
            enabled = enabled,
            singleLine = true,
            label = { Text(tr(R.string.port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Column(Modifier.weight(1f)) {
            SettingSwitch(tr(R.string.tls_handshake), input.tls, enabled) {
                onChange(input.copy(tls = it))
            }
        }
    }
    if (input.tls)
        OutlinedTextField(
            input.sni,
            { onChange(input.copy(sni = it)) },
            Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(tr(R.string.sni_certificate_hostname_optional)) },
            placeholder = { Text(tr(R.string.leave_empty_to_use_the_address_above)) },
        )
}
