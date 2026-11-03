// SPDX-License-Identifier: GPL-3.0-only
// Row heights, icon squares and insets are the grouped-list design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The rows of an iOS-style settings page: navigation rows with values, links out of the app,
// read-only facts and section footnotes. Groups are a GroupTitle, a GroupCard and a footer.

/** One group: an optional small title above the card and an optional footnote below it. */
@Composable
internal fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) GroupTitle(title)
        GroupCard(content)
        if (footer != null)
            Text(
                footer,
                Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
    }
}

/** A tinted rounded square holding a white glyph, as beside iOS Settings rows. */
@Composable
internal fun IconSquare(icon: PcIcon, tint: Color) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        AppGlyph(icon, Modifier.size(17.dp), Color.White)
    }
}

/** Opens a page: optional icon, the title, the current value in grey and a chevron. */
@Composable
internal fun NavRow(
    title: String,
    tag: String,
    value: String? = null,
    icon: Pair<PcIcon, Color>? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 50.dp)
            .fluidClickable(depth = .97f, role = Role.Button, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { (glyph, tint) -> IconSquare(glyph, tint) }
        // The title keeps its full width; a long value gives way and ends in an ellipsis.
        Text(
            title,
            if (value == null) Modifier.weight(1f) else Modifier.widthIn(max = 240.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (value != null)
            Text(
                value,
                Modifier.weight(1f),
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge,
            )
        AppGlyph(PcIcon.ChevronRight, Modifier.size(16.dp), Muted.copy(alpha = .7f))
    }
}

/** Leaves the app for a system screen; the arrow points out, not onward. */
@Composable
internal fun LinkRow(title: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 50.dp)
            .fluidClickable(enabled, depth = .97f, role = Role.Button, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        OutArrow(MaterialTheme.colorScheme.primary)
    }
}

/** A fact that cannot be changed here: the name on the left, the value in grey on the right. */
@Composable
internal fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The iOS search field: a grey capsule with a magnifier and the query typed inside. */
@Composable
internal fun SearchField(title: String, query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppGlyph(PcIcon.Search, Modifier.size(18.dp), Muted)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty())
                Text(title, color = Muted, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                query,
                onChange,
                Modifier.fillMaxWidth().testTag("field-$title").semantics {
                    contentDescription = title
                },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** The iOS selection mark: an empty ring, or a filled blue circle with a white check. */
@Composable
internal fun CheckCircle(checked: Boolean, enabled: Boolean) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    Box(
        Modifier.padding(start = 8.dp)
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.background(accent)
                else Modifier.border(1.5.dp, Muted.copy(alpha = .5f), CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) AppGlyph(PcIcon.Check, Modifier.size(16.dp), Color.White)
    }
}

/** A small north-east arrow: this row opens a screen outside the app. */
@Composable
private fun OutArrow(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.8.dp.toPx()
        val inset = size.width * .18f
        val end = Offset(size.width - inset, inset)
        drawLine(color, Offset(inset, size.height - inset), end, stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .38f, inset), end, stroke, StrokeCap.Round)
        drawLine(
            color,
            Offset(size.width - inset, size.height * .62f),
            end,
            stroke,
            StrokeCap.Round,
        )
    }
}
