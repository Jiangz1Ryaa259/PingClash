// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Input limits match the existing subscription contract.

package dev.pingclash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Full-page form. Back remains responsive during IO; blank taps have no dismissal action. */
@Suppress("LongParameterList") // Retains the existing form callback API for auxiliary callers.
@Composable
internal fun ImportDialog(
    ui: AppUiState,
    onDismiss: () -> Unit,
    onUserAgent: (String) -> Unit = {},
    onFile: (() -> Unit)? = null,
    initial: ImportDraft? = null,
    onSubmit: (String, String, Boolean) -> Unit,
) {
    val draft = initial ?: remember { ImportDraft() }
    val form = remember(draft) { draft.form ?: ImportFormState(draft).also { draft.form = it } }
    val focus = LocalFocusManager.current
    LaunchedEffect(form.content, form.subscription) {
        if (!form.nameEdited)
            form.name =
                if (form.subscription) subscriptionInput(form.content)?.suggestedName.orEmpty()
                else ""
    }
    LaunchedEffect(ui.importIssue) {
        if (ui.importIssue != null) {
            form.review = form.content.isNotBlank()
            form.error = ui.importIssue
            focus.clearFocus()
        }
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    Surface(Modifier.fillMaxSize(), color = Panel) {
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ImportHeader(ui.busy, onDismiss, onFile)
            ImportType(form, ui.busy)
            AnimatedVisibility(!form.review) { ImportFields(form, ui.busy) }
            AnimatedVisibility(form.review) { ImportReview(form, ui.busy, onUserAgent) }
            // Field error styling remains local; the explanation lives in the
            // same top-right notice flow as other failures.
            val shared = LocalIssueNotices.current
            if (shared != null) {
                LaunchedEffect(form.error) {
                    form.error?.let { error ->
                        shared.publish(
                            IssueNotice(
                                java.util.UUID.randomUUID().toString(),
                                tr(R.string.issue_warning),
                                error,
                                ui.messageStage.takeUnless { it == IssueStage.OPERATION }
                                    ?: IssueStage.IMPORT,
                                IssueSeverity.WARNING,
                                "import-warning",
                            )
                        )
                    }
                }
            } else form.error?.let { InputIssue(it) }
            if (form.review)
                TextButton({ form.review = false }, enabled = !ui.busy) {
                    Text(tr(R.string.ui_back))
                }
            Button(
                { form.next(onSubmit) },
                Modifier.fillMaxWidth().height(54.dp),
                shape = CircleShape,
                enabled = !ui.busy && (!form.subscription || validSubscriptionUa(form.agent)),
            ) {
                ImportSubmitLabel(form, ui.busy)
            }
        }
    }
}

@Composable
private fun ImportHeader(busy: Boolean, onDismiss: () -> Unit, onFile: (() -> Unit)?) {
    Row {
        IconButton(onDismiss, Modifier.testTag("import-back")) { AppGlyph(BACK_GLYPH) }
        PageHeading(tr(R.string.add_profile), Modifier.weight(1f))
        if (onFile != null)
            TextButton(onFile, enabled = !busy, modifier = Modifier.testTag("import-file")) {
                Text(tr(R.string.import_file))
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportType(form: ImportFormState, busy: Boolean) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(R.string.subscription_link, R.string.node_configuration).forEachIndexed {
            index,
            label ->
            SegmentedButton(
                form.subscription == (index == 0),
                {
                    form.subscription = index == 0
                    form.content = ""
                    form.review = false
                    form.error = null
                },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                enabled = !busy,
            ) {
                Text(tr(label))
            }
        }
    }
}

@Composable
private fun ImportFields(form: ImportFormState, busy: Boolean) {
    val detailsOpen = LocalIssueNotices.current?.detail?.value != null
    val hidden = detailsOpen || form.subscription && !form.visible
    VisiblePrivateContent(form.content.isNotBlank() && !hidden)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            form.content,
            form::edit,
            Modifier.testTag("subscription-content")
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 220.dp),
            enabled = !busy,
            singleLine = form.subscription,
            label = {
                Text(
                    tr(
                        if (form.subscription) R.string.subscription_link
                        else R.string.node_json_clash_yaml
                    )
                )
            },
            isError = form.error != null,
            shape = UiCorners.Compact,
            visualTransformation =
                if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                if (form.subscription)
                    TextButton({ form.visible = !form.visible }) {
                        Text(tr(if (form.visible) R.string.hide else R.string.show))
                    }
            },
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = if (form.subscription) KeyboardType.Uri else KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
        )
        OutlinedTextField(
            form.name,
            {
                if (it.length <= 100) {
                    form.name = it
                    form.nameEdited = true
                }
            },
            Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            shape = UiCorners.Compact,
            label = { Text(tr(R.string.name_optional)) },
            placeholder = { Text(tr(R.string.my_subscription)) },
        )
    }
}

@Composable
private fun ImportReview(form: ImportFormState, busy: Boolean, onUserAgent: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(R.string.ui_review_import), style = MaterialTheme.typography.titleLarge)
        Text(form.name.ifBlank { tr(R.string.my_subscription) })
        if (form.subscription) {
            val host =
                runCatching {
                        subscriptionInput(form.content)?.let { java.net.URI(it.source).host }
                    }
                    .getOrNull()
                    .orEmpty()
            Text(host, color = Muted)
            OutlinedTextField(
                form.agent,
                {
                    if (it.length <= 256) {
                        form.agent = it
                        onUserAgent(it)
                    }
                },
                Modifier.fillMaxWidth().testTag("subscription-ua"),
                singleLine = true,
                enabled = !busy,
                isError = !validSubscriptionUa(form.agent),
                label = { Text(tr(R.string.subscription_ua)) },
            )
        }
    }
}

@Composable
private fun ImportSubmitLabel(form: ImportFormState, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
    }
    Text(
        tr(
            when {
                busy -> R.string.importing
                !form.review -> R.string.ui_next
                form.subscription -> R.string.download_and_import
                else -> R.string.action_import
            }
        )
    )
}
