// SPDX-License-Identifier: GPL-3.0-only
// Row geometry is a deliberate design value.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** The value a row shows: a stored route, or Default when the service has none. */
internal const val ROUTE_DEFAULT = "default"

internal fun Map<String, String>.withRoute(ids: List<String>, route: String): Map<String, String> =
    if (route == ROUTE_DEFAULT) this - ids.toSet() else this + ids.associateWith { route }

/** One shared value for [ids], or Default when they differ. */
internal fun Map<String, String>.routeOf(ids: List<String>): String =
    ids.map { this[it] ?: ROUTE_DEFAULT }.distinct().singleOrNull() ?: ROUTE_DEFAULT

@Composable
internal fun rememberHomeRegion(): ServiceRegion? {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(locale) { homeRegion(locale.country, locale.language) }
}

/**
 * Settings > Service routing: ad blocking and the region tabs stay put; only the services of the
 * chosen tab scroll, inside their card. Clear all sits at the top right of the heading.
 */
@Composable
internal fun ColumnScope.ServiceRoutingPage(
    client: ClientSettings,
    enabled: Boolean,
    groups: List<String>,
    heading: @Composable (trailing: @Composable () -> Unit) -> Unit,
    onChange: (routes: Map<String, String>, blocking: String) -> Unit,
) {
    val routes = client.serviceRoutes
    val order = regionOrder(rememberHomeRegion())
    var region by rememberSaveable { mutableStateOf(order.first().name) }
    var clearing by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val language = LocalUiLanguage.current
    heading { ClearButton(enabled && routes.isNotEmpty()) { clearing = true } }
    SettingsSection {
        Choices(tr(R.string.ad_blocking), client.blocking, blockingOptions(), enabled) {
            onChange(routes, it)
        }
    }
    val tab = ServiceRegion.valueOf(region)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupTitle(tr(R.string.routing_regions))
        RegionChips(order, tab) { region = it.name }
    }
    val services = SERVICE_CATALOGUE.filter { it.region == tab }
    Surface(
        Modifier.fillMaxWidth().weight(1f, fill = false),
        shape = RoundedCornerShape(16.dp),
        color = Panel.copy(alpha = if (LocalImmersiveScene.current) .9f else 1f),
    ) {
        // A new tab starts at its top.
        val list = key(tab) { rememberLazyListState() }
        LazyColumn(
            Modifier.fillMaxWidth().testTag("routing-list"),
            state = list,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            itemsIndexed(services, key = { _, service -> service.id }) { index, service ->
                if (index > 0) RowDivider(underIcons = false)
                val detail = routeDetail(routes[service.id], groups)
                RouteRow(
                    RouteLabel(
                        service.label(language),
                        service.id,
                        detail,
                        if (detail != null && enabled) ({ picking = service.id }) else null,
                    ),
                    routes.routeOf(listOf(service.id)),
                    enabled,
                ) {
                    onChange(routes.withRoute(listOf(service.id), it), client.blocking)
                }
            }
        }
    }
    if (clearing) ClearDialog({ clearing = false }) { onChange(emptyMap(), client.blocking) }
    picking?.let { id ->
        GroupDialog(routes[id], groups, { picking = null }) { route ->
            onChange(routes + (id to route), client.blocking)
        }
    }
}

@Composable
private fun ClearButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled, modifier = Modifier.testTag("routing-clear-all")) {
        Text(
            tr(R.string.routing_clear_all),
            color = pageInk().danger.copy(alpha = if (enabled) 1f else .38f),
        )
    }
}

@Composable
private fun ClearDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.routing_clear_title)) },
        text = { Text(tr(R.string.routing_clear_body)) },
        confirmButton = {
            TextButton(
                {
                    onDismiss()
                    onClear()
                },
                Modifier.testTag("routing-clear-confirm"),
            ) {
                Text(tr(R.string.routing_clear_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

internal fun regionLabel(region: ServiceRegion) =
    listOf(region.flag, tr(region.title)).filter { it.isNotEmpty() }.joinToString(" ")

/** The tabs: one capsule per country, region or worldwide group, scrolling sideways. */
@Composable
private fun RegionChips(
    order: List<ServiceRegion>,
    selected: ServiceRegion,
    onSelect: (ServiceRegion) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("routing-regions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        order.forEach { region ->
            val chosen = region == selected
            Text(
                regionLabel(region),
                Modifier.clip(RoundedCornerShape(50))
                    .background(
                        if (chosen) MaterialTheme.colorScheme.primary
                        // The card surface: chips sit on the scene, not inside a card.
                        else Panel.copy(alpha = if (LocalImmersiveScene.current) .9f else 1f)
                    )
                    .selectable(chosen, role = Role.Tab) { onSelect(region) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("routing-region-${region.name}"),
                color =
                    if (chosen) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * What a route row shows: the service's [name], its test [tag], and an optional [detail] line that
 * [onDetail] makes tappable.
 */
internal data class RouteLabel(
    val name: String,
    val tag: String,
    val detail: String? = null,
    val onDetail: (() -> Unit)? = null,
)

private val MIN_ROUTE_NAME = 92.dp
private val MIN_ROUTE_CHOICE = 168.dp

/** Wide enough for every option at a readable 12 sp, and never narrower than before. */
@Composable
private fun routeChoiceWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style =
        MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    return remember(labels, density) {
        val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }
        // Each segment pads its text by 6 dp a side; the track adds 2 dp a side.
        val needed = with(density) { (widest.toDp() + 14.dp) * labels.size + 4.dp }
        maxOf(MIN_ROUTE_CHOICE, needed)
    }
}

/**
 * A service and its three-way choice. Wide enough, the choice sits beside the name; on a narrow
 * screen or with large text it goes below it.
 */
@Composable
internal fun RouteRow(
    label: RouteLabel,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val tag = label.tag
    val options =
        listOf(
            ROUTE_PROXY to tr(R.string.routing_proxy),
            ROUTE_DIRECT to tr(R.string.routing_direct),
            ROUTE_DEFAULT to tr(R.string.routing_default),
        )
    val large = LocalDensity.current.fontScale >= 1.3f
    val choiceWidth = routeChoiceWidth(options.map { it.second })
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("route-$tag")) {
        val name: @Composable () -> Unit = {
            Column {
                Text(label.name, style = MaterialTheme.typography.bodyLarge)
                if (label.detail != null) RouteDetail(label)
            }
        }
        val choice: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier) {
                // A route through a group is still Proxy; the line under the name says which.
                val shown = if (routeGroup(value) != null) ROUTE_PROXY else value
                SegmentedControl(options, shown, enabled, { "route-$tag-$it" }, onChange)
            }
        }
        // Beside the name while both fit; longer translations put the choice under the name.
        if (maxWidth - choiceWidth >= MIN_ROUTE_NAME && !large)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.weight(1f)) { name() }
                choice(Modifier.width(choiceWidth))
            }
        else
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                name()
                choice(Modifier.fillMaxWidth())
            }
    }
}
