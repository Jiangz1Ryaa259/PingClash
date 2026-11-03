// SPDX-License-Identifier: GPL-3.0-only
// Switch touch targets and segmented control dimensions are visual design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
internal fun ModeSegments(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val options =
        listOf(
            "rule" to tr(R.string.rules),
            "global" to tr(R.string.global_proxy),
            "direct" to tr(R.string.direct),
        )
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr(R.string.routing_mode), style = MaterialTheme.typography.bodyLarge)
        // Very large text cannot fit three segments: a checkmark list reads better there.
        if (LocalDensity.current.fontScale >= 1.6f)
            CheckList(options, value, enabled, { "mode-$it" }, onChange)
        else SegmentedControl(options, value, enabled, { "mode-$it" }, onChange)
    }
}

/**
 * The iOS segmented control: a grey track with a raised thumb that slides to the chosen option.
 * Each option keeps one line in every language, shrinking rather than wrapping.
 */
@Composable
internal fun SegmentedControl(
    options: List<Pair<String, String>>,
    value: String,
    enabled: Boolean,
    tag: (String) -> String,
    onChange: (String) -> Unit,
) {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = if (light) .07f else .16f)
    val thumb = if (light) Color.White else Color(0xFF636366)
    val index = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    val shape = RoundedCornerShape(9.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).background(track).padding(2.dp)) {
        val width = maxWidth / options.size
        val offset by animateDpAsState(width * index, label = "segment-thumb")
        Box(
            Modifier.offset(x = offset)
                .width(width)
                .height(SEGMENT_HEIGHT)
                .shadow(if (light) 1.dp else 0.dp, RoundedCornerShape(7.dp))
                .background(thumb, RoundedCornerShape(7.dp))
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEach { (id, label) ->
                Box(
                    Modifier.weight(1f)
                        .height(SEGMENT_HEIGHT)
                        .selectable(
                            value == id,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = null,
                            indication = null,
                            onClick = { if (value != id) onChange(id) },
                        )
                        .testTag(tag(id)),
                    contentAlignment = Alignment.Center,
                ) {
                    val style = MaterialTheme.typography.labelLarge
                    BasicText(
                        label,
                        Modifier.padding(horizontal = 6.dp),
                        style =
                            style.copy(
                                color =
                                    MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (enabled) 1f else .4f
                                    ),
                                fontWeight =
                                    if (value == id) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        maxLines = 1,
                        autoSize =
                            TextAutoSize.StepBased(
                                minFontSize = 10.sp,
                                maxFontSize = style.fontSize,
                            ),
                    )
                }
            }
        }
    }
}

/** Options as rows with a checkmark on the chosen one, as in iOS lists. */
@Composable
internal fun CheckList(
    options: List<Pair<String, String>>,
    value: String,
    enabled: Boolean,
    tag: (String) -> String,
    onChange: (String) -> Unit,
) {
    Column {
        options.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        value == id,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { if (value != id) onChange(id) },
                    )
                    .testTag(tag(id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f))
                if (value == id)
                    AppGlyph(PcIcon.Check, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val SEGMENT_HEIGHT = 34.dp
