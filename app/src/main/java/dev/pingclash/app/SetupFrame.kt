// SPDX-License-Identifier: GPL-3.0-only
// Layout spacing is a deliberate design value.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupTopBar(step: Int, agreementOnly: Boolean, onSkipAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!agreementOnly)
            Text(
                tr(R.string.setup_step, step, SETUP_READY),
                Modifier.weight(1f),
                color = Muted,
                style = MaterialTheme.typography.labelLarge,
            )
        else Box(Modifier.weight(1f))
        if (step > SETUP_AGREEMENT && step < SETUP_READY)
            TextButton(onSkipAll, Modifier.testTag("setup-skip-all")) {
                Text(tr(R.string.setup_skip_all))
            }
    }
}

/** A step: its title, its scrolling content, and the buttons pinned below. */
@Composable
internal fun ColumnScope.StepFrame(
    title: String,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            title,
            Modifier.testTag("setup-title"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons()
    }
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit) {
    Button(onClick, Modifier.testTag(tag)) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.testTag(tag)) { Text(text) }
}

@Composable
internal fun SetupBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
