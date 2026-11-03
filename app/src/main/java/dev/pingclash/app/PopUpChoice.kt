// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A pop-up button: the setting's name on the left, a capsule with the current value on the right,
 * and a menu with a checkmark column when tapped. Unlike segments it keeps its size however many
 * options a list grows to (languages, scenes).
 */
@Composable
internal fun PopUpChoice(
    title: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.first == value }?.second ?: value
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
                modifier =
                    Modifier.widthIn(max = 220.dp)
                        .fluidClickable(enabled, role = Role.DropdownList) { open = true }
                        .testTag("choice-$title")
                        .semantics {
                            contentDescription = title
                            stateDescription = current
                        },
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(6.dp))
                    AppGlyph(PcIcon.ChevronDown, Modifier.size(16.dp), Muted)
                }
            }
            DropdownMenu(open, { open = false }, shape = RoundedCornerShape(16.dp)) {
                choices.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            if (id != value) onChange(id)
                        },
                        // A fixed checkmark column keeps every label aligned, as on iOS.
                        leadingIcon = {
                            if (id == value) AppGlyph(PcIcon.Check, Modifier.size(18.dp), Mint)
                            else Spacer(Modifier.size(18.dp))
                        },
                        modifier = Modifier.testTag("choice-$title-$id"),
                    )
                }
            }
        }
    }
}
