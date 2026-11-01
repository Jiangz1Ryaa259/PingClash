// SPDX-License-Identifier: GPL-3.0-only
// Switch touch targets and segmented control dimensions are visual design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingSwitch(
    title: String,
    value: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("switch-$title")
            .toggleable(
                value = value,
                interactionSource = interactions,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f))
        LiquidSwitch(value, enabled, interactions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeSegments(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val options =
        listOf(
            "rule" to tr(R.string.rules),
            "global" to tr(R.string.global_proxy),
            "direct" to tr(R.string.direct),
        )
    Text(tr(R.string.routing_mode), color = Muted, style = MaterialTheme.typography.labelLarge)
    if (LocalDensity.current.fontScale >= 1.6f)
        Column {
            options.forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("mode-$id")
                        .selectable(
                            value == id,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onChange(id) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(value == id, null, enabled = enabled)
                    Text(label)
                }
            }
        }
    else
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (id, label) ->
                SegmentedButton(
                    selected = value == id,
                    onClick = { onChange(id) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mode-$id"),
                ) {
                    Text(label)
                }
            }
        }
}
