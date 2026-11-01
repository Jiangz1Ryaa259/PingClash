// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class ProxyDetailTarget(val group: String, val name: String)

internal class ProxyDetailActions(
    val dismiss: () -> Unit,
    val urlTest: (() -> Unit)?,
    val tcpTest: (() -> Unit)?,
    val diagnose: (() -> Unit)?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProxyMemberDetails(
    state: ProxyMemberPresentation,
    tcp: ProxyDelay?,
    udpOnly: Boolean,
    actions: ProxyDetailActions,
) {
    ModalBottomSheet(onDismissRequest = actions.dismiss) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .testTag("proxy-member-details")
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                state.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(state.description, style = MaterialTheme.typography.bodyMedium, color = Muted)
            HorizontalDivider()
            DetailMetric(tr(R.string.proxy_url_latency), proxyLatencyText(state.delay))
            DetailMetric("TCP", tcpDetailText(udpOnly, tcp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    { actions.urlTest?.invoke() },
                    enabled = actions.urlTest != null,
                    modifier = Modifier.weight(1f).testTag("proxy-detail-url"),
                ) {
                    Text(tr(R.string.proxy_test_url))
                }
                OutlinedButton(
                    { actions.tcpTest?.invoke() },
                    enabled = actions.tcpTest != null,
                    modifier = Modifier.weight(1f).testTag("proxy-detail-tcp"),
                ) {
                    Text(tr(R.string.proxy_test_tcp))
                }
            }
            Button(
                { actions.diagnose?.invoke() },
                enabled = actions.diagnose != null,
                modifier = Modifier.fillMaxWidth().testTag("proxy-detail-diagnose"),
            ) {
                Text(tr(R.string.proxy_diagnose_member))
            }
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
