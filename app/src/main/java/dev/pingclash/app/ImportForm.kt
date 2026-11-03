// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Input limits match the existing subscription contract.

package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Add a profile on one grouped page: a subscription link or pasted nodes, an optional name, and for
 * links the host it will download from. Back remains responsive during IO; blank taps have no
 * dismissal action.
 */
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
            form.error = ui.importIssue
            focus.clearFocus()
        }
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ImportHeader(ui.busy, onDismiss, onFile)
            ImportType(form, ui.busy)
            if (form.subscription) {
                LinkSection(form, ui.busy)
                AdvancedSection(form, ui.busy, onUserAgent)
            } else NodeSection(form, ui.busy)
            ImportIssue(form, ui)
            Button(
                { form.next(onSubmit) },
                Modifier.fillMaxWidth().height(54.dp).testTag("import-submit"),
                shape = CircleShape,
                enabled = !ui.busy && (!form.subscription || validSubscriptionUa(form.agent)),
            ) {
                ImportSubmitLabel(form, ui.busy)
            }
        }
    }
}

/**
 * Field error styling stays on the form; the explanation goes to the same top-right notice flow as
 * other failures.
 */
@Composable
private fun ImportIssue(form: ImportFormState, ui: AppUiState) {
    val shared = LocalIssueNotices.current
    if (shared == null) {
        form.error?.let { InputIssue(it) }
        return
    }
    LaunchedEffect(form.error) {
        form.error?.let { error ->
            shared.publish(
                IssueNotice(
                    java.util.UUID.randomUUID().toString(),
                    tr(R.string.issue_warning),
                    error,
                    ui.messageStage.takeUnless { it == IssueStage.OPERATION } ?: IssueStage.IMPORT,
                    IssueSeverity.WARNING,
                    "import-warning",
                )
            )
        }
    }
}

/** A blue "< Profiles" back button, Import file on the right, then the large title. */
@Composable
private fun ImportHeader(busy: Boolean, onDismiss: () -> Unit, onFile: (() -> Unit)?) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = tr(R.string.back)
            Row(
                Modifier.fluidClickable(onClick = onDismiss)
                    .testTag("import-back")
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppGlyph(BACK_GLYPH, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                Text(tr(R.string.profiles), color = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.weight(1f))
            if (onFile != null)
                TextButton(onFile, enabled = !busy, modifier = Modifier.testTag("import-file")) {
                    Text(tr(R.string.import_file))
                }
        }
        PageHeading(tr(R.string.add_profile))
    }
}

@Composable
private fun ImportType(form: ImportFormState, busy: Boolean) {
    SegmentedControl(
        listOf(
            "subscription" to tr(R.string.subscription_link),
            "node" to tr(R.string.node_configuration),
        ),
        if (form.subscription) "subscription" else "node",
        !busy,
        { "import-type-$it" },
    ) {
        form.subscription = it == "subscription"
        form.content = ""
        form.error = null
    }
}

/** The link, hidden like a password until shown, the name, and where it downloads from. */
@Composable
private fun LinkSection(form: ImportFormState, busy: Boolean) {
    val detailsOpen = LocalIssueNotices.current?.detail?.value != null
    val hidden = detailsOpen || !form.visible
    VisiblePrivateContent(form.content.isNotBlank() && !hidden)
    val host =
        subscriptionInput(form.content)?.let { input ->
            runCatching { httpsAuthority(java.net.URI(input.source))?.first }.getOrNull()
        }
    SettingsSection(
        footer =
            if (host != null) tr(R.string.ui_import_downloads_from, host)
            else tr(R.string.ui_import_https_only)
    ) {
        LinkField(form, busy, hidden)
        RowDivider(underIcons = false)
        NameRow(form, busy)
    }
}

/** The link itself, with an eye to show or hide it. */
@Composable
private fun LinkField(form: ImportFormState, busy: Boolean, hidden: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (form.content.isEmpty())
                Text(
                    tr(R.string.subscription_link),
                    color = Muted.copy(alpha = .7f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            BasicTextField(
                form.content,
                form::edit,
                Modifier.fillMaxWidth().testTag("subscription-content").semantics {
                    if (hidden) password()
                },
                enabled = !busy,
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color =
                            if (form.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation =
                    if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
            )
        }
        val toggle = tr(if (form.visible) R.string.hide else R.string.show)
        Box(
            Modifier.size(40.dp)
                .fluidClickable(depth = .9f) { form.visible = !form.visible }
                .semantics { contentDescription = toggle },
            contentAlignment = Alignment.Center,
        ) {
            AppGlyph(if (form.visible) PcIcon.EyeOff else PcIcon.Eye, Modifier.size(20.dp), Muted)
        }
    }
}

@Composable
internal fun NameRow(form: ImportFormState, busy: Boolean) {
    TextFieldRow(
        tr(R.string.name_optional),
        form.name,
        !busy,
        placeholder = if (form.subscription) tr(R.string.my_subscription) else "",
    ) {
        if (it.length <= 100) {
            form.name = it
            form.nameEdited = true
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
                form.subscription -> R.string.download_and_import
                else -> R.string.action_import
            }
        )
    )
}
