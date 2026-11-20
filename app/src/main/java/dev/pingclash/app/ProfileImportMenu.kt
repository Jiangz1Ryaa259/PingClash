// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The add button opens a glass sheet: ways to import (link, file, QR code), each opening a small
 * menu of its variants, and ways to enter a profile by hand (one node, or a subscription with its
 * name and User-Agent).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileImportMenu(
    compact: Boolean,
    locked: Boolean,
    modifier: Modifier,
    onImport: (ImportMethod) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(locked) { if (locked) open = false }
    GlassButton(
        { if (!locked) open = true },
        modifier.testTag("profile-add").semantics {
            contentDescription = tr(R.string.add_subscription)
            if (locked) disabled()
        },
        enabled = !locked,
    ) {
        AppGlyph(PcIcon.Add, Modifier.size(20.dp))
        if (!compact) Text(tr(R.string.add_subscription))
    }
    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // The chosen page opens at once, over the sheet, which closes underneath.
        fun choose(method: ImportMethod) {
            open = false
            onImport(method)
        }
        GlassSheet({ open = false }, Modifier.testTag("import-sheet"), sheetState = sheet) {
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
            ) {
                Text(
                    tr(R.string.add_subscription),
                    Modifier.padding(start = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                ImportSection(
                    tr(R.string.import_section_import),
                    listOf(ImportGroup.LINK, ImportGroup.FILE, ImportGroup.QR),
                    ::choose,
                )
                ImportSection(
                    tr(R.string.import_section_by_hand),
                    listOf(ImportGroup.NODE, ImportGroup.SUBSCRIPTION),
                    ::choose,
                )
            }
        }
    }
}

@Composable
private fun ImportSection(
    title: String,
    groups: List<ImportGroup>,
    onChoose: (ImportMethod) -> Unit,
) {
    Text(
        title,
        Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
        color = Muted,
        style = MaterialTheme.typography.labelLarge,
    )
    Column(Modifier.fillMaxWidth().glassPanel(GlassCorner)) {
        groups.forEachIndexed { index, group ->
            if (index > 0) RowDivider()
            ImportGroupRow(group, onChoose)
        }
    }
}

/** A way to add: one method opens at once; several open a menu from the row. */
@Composable
private fun ImportGroupRow(group: ImportGroup, onChoose: (ImportMethod) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val single = group.methods.singleOrNull()
    Box {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 62.dp)
                .glassClickable { if (single != null) onChoose(single) else menu = true }
                .testTag(
                    if (single != null) "import-method-${single.name.lowercase()}"
                    else "import-group-${group.name.lowercase()}"
                )
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconSquare(group.glyph, MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
            Column(Modifier.weight(1f)) {
                Text(tr(group.title), style = MaterialTheme.typography.bodyLarge)
                Text(tr(group.hint), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            AppGlyph(PcIcon.ChevronRight, Modifier.size(16.dp), Muted)
        }
        GlassMenu(menu, { menu = false }) {
            group.methods.forEach { method ->
                GlassMenuItem(
                    { Text(tr(method.title)) },
                    {
                        menu = false
                        onChoose(method)
                    },
                    Modifier.testTag("import-method-${method.name.lowercase()}"),
                    leadingIcon = { AppGlyph(method.glyph, Modifier.size(20.dp)) },
                )
            }
        }
    }
}
