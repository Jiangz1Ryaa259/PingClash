// SPDX-License-Identifier: GPL-3.0-only
// Row heights, insets and corner radii are the grouped-list design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// iOS-style grouped list pieces: a section title, a rounded card of rows and inset dividers.

@Composable
internal fun GroupTitle(text: String) {
    // Bold and solid, as Apple sets section titles that sit straight on the page.
    Text(
        text,
        Modifier.padding(start = 6.dp, top = 10.dp),
        style = MaterialTheme.typography.labelLarge.onPage(),
        fontWeight = FontWeight.Bold,
        color = pageInk().text,
    )
}

@Composable
internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Panel.copy(alpha = if (LocalImmersiveScene.current) .9f else 1f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), content = content)
    }
}

@Composable
internal fun RowDivider(underIcons: Boolean = true) {
    // Under icon rows the line starts at the text, as in iOS; plain rows use the full width.
    HorizontalDivider(
        Modifier.padding(start = if (underIcons) 42.dp else 0.dp),
        thickness = .5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A settings row led by a small tinted icon square, as in iOS Settings. */
@Composable
internal fun IconRow(icon: PcIcon, tint: Color, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(tint),
            contentAlignment = Alignment.Center,
        ) {
            AppGlyph(icon, Modifier.size(17.dp), Color.White)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
internal fun ActionRow(
    title: String,
    tag: String,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 50.dp)
            .fluidClickable(enabled, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = if (enabled) color else color.copy(alpha = .38f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
