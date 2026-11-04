// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeServiceChecks(
    state: DashboardState,
    onTest: (List<String>) -> Unit,
    shownCount: Int = 4,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = serviceTargets.take(shownCount.coerceIn(2, 4))
    Surface(
        shape = UiCorners.Card,
        color = Panel.copy(alpha = .86f),
        border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("home-service-checks"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(R.string.ui_site_latency),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    { expanded = true },
                    Modifier.testTag("service-check-more").semantics {
                        contentDescription = tr(R.string.ui_more)
                    },
                ) {
                    AppGlyph(PcIcon.ChevronDown)
                }
                IconButton(
                    { onTest(shown.map { it.id }) },
                    enabled = state.services.running || websiteMeasurementEnabled(state),
                    modifier =
                        Modifier.testTag("service-check-start").semantics {
                            contentDescription =
                                tr(
                                    if (state.services.running) R.string.cancel
                                    else R.string.ui_measure
                                )
                        },
                ) {
                    AppGlyph(if (state.services.running) PcIcon.Power else PcIcon.Bolt)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { target ->
                    ServiceTile(target, state, Modifier.weight(1f)) { onTest(listOf(target.id)) }
                }
            }
        }
    }
    if (expanded)
        ModalBottomSheet(onDismissRequest = { expanded = false }, containerColor = Panel) {
            ServiceTargetsPanel(state, onTest)
        }
}

@Composable
private fun ServiceTile(
    target: ServiceTarget,
    state: DashboardState,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = 48.dp)
            .testTag("service-target-${target.id}")
            .fluidClickable(
                depth = .97f,
                enabled = websiteMeasurementEnabled(state) && !state.services.running,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            target.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ServiceLatency(state.services.values[target.id])
    }
}

@Composable
private fun ServiceTargetsPanel(state: DashboardState, onTest: (List<String>) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .fillMaxHeight(.8f)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.ui_site_latency),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                { onTest(serviceTargets.map { it.id }) },
                enabled = state.services.running || websiteMeasurementEnabled(state),
            ) {
                Text(tr(if (state.services.running) R.string.cancel else R.string.ui_measure_all))
            }
        }

        serviceTargets.forEach { target ->
            WebsiteCheckChip(
                target,
                state.services.values[target.id],
                websiteMeasurementEnabled(state),
            ) {
                onTest(listOf(target.id))
            }
        }
    }
}

@Composable
private fun ServiceLatency(result: Latency?) {
    val tone =
        when (result) {
            is Latency.Measured -> latencyColor(result.elapsed)
            is Latency.Failed -> MaterialTheme.colorScheme.error
            else -> Muted
        }
    AnimatedContent(result, label = "service-latency") { value ->
        Text(
            when (value) {
                is Latency.Measured -> "${value.elapsed} ms"
                Latency.Running -> tr(R.string.ui_checking)
                is Latency.Failed -> websiteFailureText(value)
                null -> tr(R.string.ui_not_measured)
            },
            color = tone,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun websiteFailureText(value: Latency.Failed): String =
    when (value.reason) {
        WebsiteFailure.TIMEOUT -> tr(R.string.timeout)
        WebsiteFailure.RESET -> tr(R.string.website_reset)
        WebsiteFailure.DNS -> tr(R.string.website_dns)
        WebsiteFailure.CERTIFICATE -> tr(R.string.website_certificate)
        WebsiteFailure.REFUSED -> tr(R.string.website_refused)
        WebsiteFailure.NETWORK -> tr(R.string.website_no_network)
        WebsiteFailure.CANCELLED -> tr(R.string.ui_not_measured)
        WebsiteFailure.RESTRICTED -> tr(R.string.website_restricted)
        WebsiteFailure.OTHER -> tr(R.string.failed)
    }
