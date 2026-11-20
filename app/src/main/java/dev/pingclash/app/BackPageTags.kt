// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Test tags of a [BackPage]'s back row and title. */
internal class BackPageTags(val back: String = "diagnosis-back", val title: String = "page-heading")

/**
 * A scrolling page opened from another: a back row at the page's edge and the title under it, as
 * the Settings pages have, then cards.
 */
@Composable
internal fun BackPage(
    title: String,
    onBack: () -> Unit,
    tags: BackPageTags = BackPageTags(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .imePadding()
            // Keyed by the title: a page opened from another starts at its own top.
            .verticalScroll(key(title) { rememberScrollState() })
            .readableWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .padding(bottom = LocalNavigationClearance.current),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            val label = tr(R.string.back)
            Row(
                Modifier.fluidClickable(depth = .95f, onClick = onBack)
                    .testTag(tags.back)
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppGlyph(BACK_GLYPH, Modifier.size(22.dp), pageInk().accent)
                Text(
                    label,
                    color = pageInk().accent,
                    style = MaterialTheme.typography.bodyLarge.onPage(),
                )
            }
            PageHeading(title, tag = tags.title)
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}
