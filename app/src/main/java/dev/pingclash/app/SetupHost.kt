// SPDX-License-Identifier: GPL-3.0-only
// Card spacing is a deliberate design value.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Runs the guide for [view] and records how it ended. [onDone] receives whether to connect now. */
@Composable
internal fun SetupHost(
    view: String,
    setup: SetupState,
    data: AppData,
    model: AppViewModel,
    onDone: (connect: Boolean) -> Unit,
) {
    val activity = LocalActivity.current
    // Fixed for this run: agreeing must not move the first step under the guide.
    val first =
        remember(view) {
            when {
                view == SETUP_VIEW_AGREEMENT -> SETUP_AGREEMENT
                view == SETUP_VIEW_RERUN || setup.agreement >= AGREEMENT_VERSION -> SETUP_HOW
                else -> SETUP_LANGUAGE
            }
        }
    SetupGuide(
        data,
        model,
        first,
        agreementOnly = view == SETUP_VIEW_AGREEMENT,
        SetupActions(
            agree = { setup.agree(upgrade = view == SETUP_VIEW_AGREEMENT) },
            // Not agreeing leaves the app; the agreement shows again on the next launch.
            exit = { activity?.finishAndRemoveTask() },
            finish = { skipped, connect ->
                setup.finish(skipped)
                onDone(connect)
            },
        ),
    )
}

/** Shown once on the home page after an upgrade: service routing is new. */
@Composable
internal fun RoutingFeatureCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 104.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.readableWidth(480.dp).testTag("routing-feature-card")) {
            GroupCard {
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        tr(R.string.routing_feature_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(tr(R.string.routing_feature_body), color = Muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onDismiss, Modifier.testTag("routing-feature-dismiss")) {
                            Text(tr(R.string.routing_feature_dismiss))
                        }
                        TextButton(onOpen, Modifier.testTag("routing-feature-open")) {
                            Text(
                                tr(R.string.routing_feature_open),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
