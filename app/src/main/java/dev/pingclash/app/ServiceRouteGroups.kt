// SPDX-License-Identifier: GPL-3.0-only
// Row geometry is a deliberate design value.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The line under a proxied service: which proxy it uses, tappable when there is a choice. A group
 * this profile does not have says so, because the rules use the main proxy instead.
 */
internal fun routeDetail(route: String?, groups: List<String>): String? {
    val group = routeGroup(route)
    return when {
        group != null && group in groups -> group
        group != null -> tr(R.string.routing_group_missing, group)
        route == ROUTE_PROXY && groups.isNotEmpty() -> tr(R.string.routing_main_proxy)
        else -> null
    }
}

/** Chooses the main proxy or one of the active profile's groups for a service. */
@Composable
internal fun GroupDialog(
    route: String?,
    groups: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val options =
        listOf(ROUTE_PROXY to tr(R.string.routing_main_proxy)) + groups.map { groupRoute(it) to it }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.routing_group_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("routing-groups")) {
                items(options.size) { index ->
                    val (value, label) = options[index]
                    Text(
                        label,
                        Modifier.fillMaxWidth()
                            .selectable(value == route, role = Role.RadioButton) {
                                onPick(value)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp)
                            .testTag("route-group-$label"),
                        color =
                            if (value == route) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (value == route) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                    )
                }
            }
        },
        confirmButton = { GlassTextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
internal fun RouteAccessLine(tag: String, access: RouteAccess) {
    val running = access.result?.status == UnlockStatus.RUNNING
    Text(
        unlockText(access.result, LocalUiLanguage.current),
        Modifier.clickable(enabled = !running, role = Role.Button, onClick = access.onCheck)
            .heightIn(min = 24.dp)
            .padding(top = 3.dp)
            .testTag("route-$tag-access"),
        color = if (access.result == null) MaterialTheme.colorScheme.primary else Muted,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun RouteDetail(label: RouteLabel) {
    val pick = label.onDetail
    Row(
        Modifier.then(
                if (pick == null) Modifier
                else
                    Modifier.clickable(role = Role.Button, onClick = pick)
                        .testTag("route-${label.tag}-group")
            )
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val tint = if (pick == null) Muted else MaterialTheme.colorScheme.primary
        Text(
            label.detail.orEmpty(),
            Modifier.weight(1f, fill = false),
            color = tint,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (pick != null) AppGlyph(PcIcon.ChevronDown, Modifier.size(14.dp), tint)
    }
}
