// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Input limits match the existing subscription contract.

package dev.pingclash.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun NodeSection(form: ImportFormState, busy: Boolean) {
    // Pasted nodes carry credentials: the screen is protected while they are on it.
    val detailsOpen = LocalIssueNotices.current?.detail?.value != null
    VisiblePrivateContent(form.content.isNotBlank() && !detailsOpen)
    SettingsSection {
        NodeField(form, busy, masked = detailsOpen)
        RowDivider(underIcons = false)
        NameRow(form, busy)
    }
}

/**
 * Pasted nodes as plain text. While an error's details are open the screen may be captured, so the
 * text is masked like a password instead.
 */
@Composable
private fun NodeField(form: ImportFormState, busy: Boolean, masked: Boolean) {
    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp).padding(vertical = 12.dp)) {
        if (form.content.isEmpty())
            Text(
                tr(R.string.node_json_clash_yaml),
                color = Muted.copy(alpha = .6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        BasicTextField(
            form.content,
            form::edit,
            Modifier.fillMaxWidth()
                .heightIn(min = 176.dp)
                .testTag("subscription-content")
                .semantics { if (masked) password() },
            enabled = !busy,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation =
                if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
    }
}

/**
 * The User-Agent sent with the download. Providers choose the format by it (Clash YAML for a Clash
 * or mihomo agent), so it is needed, but the default suits nearly every provider: it stays folded
 * away unless changed.
 */
@Composable
internal fun AdvancedSection(form: ImportFormState, busy: Boolean, onUserAgent: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(form.agent != DEFAULT_SUBSCRIPTION_UA) }
    SettingsSection(tr(R.string.ui_advanced), tr(R.string.ui_subscription_ua_hint)) {
        if (!open)
        // The section footer explains it; the short name leaves room for the value.
        NavRow("User-Agent", "subscription-ua", value = form.agent) { open = true }
        else {
            TextFieldRow(tr(R.string.subscription_ua), form.agent, !busy) {
                if (it.length <= 256) {
                    form.agent = it
                    onUserAgent(it)
                }
            }
            RowDivider(underIcons = false)
            ActionRow(
                tr(R.string.ui_reset_default),
                "subscription-ua-reset",
                enabled = !busy && form.agent != DEFAULT_SUBSCRIPTION_UA,
            ) {
                form.agent = DEFAULT_SUBSCRIPTION_UA
                onUserAgent(DEFAULT_SUBSCRIPTION_UA)
            }
        }
    }
}
