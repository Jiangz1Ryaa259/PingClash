// SPDX-License-Identifier: GPL-3.0-only
// Layout geometry follows the existing settings spacing.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RoutingFilters(
    val query: String = "",
    val selected: Boolean = false,
    val system: Boolean = false,
)

private val RoutingFiltersSaver =
    androidx.compose.runtime.saveable.listSaver<RoutingFilters, Any>(
        save = { listOf(it.query, it.selected, it.system) },
        restore = { RoutingFilters(it[0] as String, it[1] as Boolean, it[2] as Boolean) },
    )

private data class RoutingApp(val name: String, val packageName: String, val system: Boolean)

/** One lazy viewport owns the heading, filters, applications and manual entries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppRoutingPanel(
    draft: ClientSettings,
    enabled: Boolean,
    heading: @Composable () -> Unit,
    onChange: (ClientSettings) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val icons = remember(context.applicationContext, configuration) { AppIconLoader(context) }
    // Pull down anywhere on the page to rescan the installed applications.
    var revision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val installed = installedRoutingApps(revision) { refreshing = false }
    val notices = LocalIssueNotices.current
    val scroll = rememberRoutingScroll(installed != null)
    val available = installed.orEmpty()
    var filters by
        rememberSaveable(stateSaver = RoutingFiltersSaver) { mutableStateOf(RoutingFilters()) }
    var manual by rememberSaveable { mutableStateOf("") }
    val selected = draft.packages.toSet()
    val listed = remember(available, selected, filters) { filtered(available, selected, filters) }
    val missing =
        if (installed != null) selected - available.map { it.packageName }.toSet() else emptySet()
    fun toggle(pkg: String) {
        onChange(
            draft.copy(
                packages =
                    if (pkg in selected) draft.packages - pkg else (draft.packages + pkg).take(300)
            )
        )
    }
    PullToRefreshBox(
        refreshing,
        onRefresh = {
            refreshing = true
            revision++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("app-list"), state = scroll) {
            item(key = "heading") { Box(Modifier.padding(bottom = 16.dp)) { heading() } }
            item(key = "filters") {
                RoutingFilterControls(
                    draft.appMode,
                    enabled,
                    filters,
                    { onChange(draft.copy(appMode = it)) },
                    { filters = it },
                )
                Spacer(Modifier.height(12.dp))
            }
            routingApplicationItems(
                listed,
                icons,
                selected,
                enabled && draft.appMode != "all",
                ::toggle,
            )
            manualRoutingItems(
                draft,
                enabled,
                ManualPackageInput(manual, { manual = it }) {
                    notices?.publish(invalidPackageNotice())
                },
                onChange,
                missing.filter { it.contains(filters.query, true) },
            )
        }
    }
}

private fun invalidPackageNotice() =
    IssueNotice(
        java.util.UUID.randomUUID().toString(),
        tr(R.string.issue_warning),
        tr(R.string.package_name_invalid),
        IssueStage.OPERATION,
        IssueSeverity.WARNING,
        "package-invalid",
    )

private fun readRoutingApps(context: android.content.Context): List<RoutingApp> =
    context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .mapNotNull { info ->
            info.activityInfo
                ?.packageName
                ?.takeIf { it != context.packageName }
                ?.let {
                    RoutingApp(
                        info.loadLabel(context.packageManager).toString().take(100),
                        it,
                        (info.activityInfo.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
        }
        .distinctBy { it.packageName }
        .sortedBy { it.name }
        .take(500)

@Composable
private fun RoutingApplicationRow(
    app: RoutingApp,
    icons: AppIconLoader,
    selected: Boolean,
    enabled: Boolean,
    toggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .fluidClickable(enabled, depth = .97f, onClick = toggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoutingAppIcon(app.packageName, icons)
        Checkbox(selected, { toggle() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoutingFilterControls(
    mode: String,
    enabled: Boolean,
    filters: RoutingFilters,
    onMode: (String) -> Unit,
    onFilters: (RoutingFilters) -> Unit,
) {
    SettingsCard {
        Choices(
            tr(R.string.mode),
            mode,
            listOf(
                "all" to tr(R.string.all_apps),
                "exclude" to tr(R.string.exclude_selected_apps),
                "include" to tr(R.string.only_selected_apps),
            ),
            enabled,
            onMode,
        )
        SettingField(tr(R.string.search_apps), filters.query, true) {
            onFilters(filters.copy(query = it.take(100)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                filters.selected,
                { onFilters(filters.copy(selected = !filters.selected)) },
                label = { Text(tr(R.string.settings_selected_apps)) },
            )
            FilterChip(
                filters.system,
                { onFilters(filters.copy(system = !filters.system)) },
                label = { Text(tr(R.string.settings_system_apps)) },
            )
        }
    }
}

private fun LazyListScope.routingApplicationItems(
    listed: List<RoutingApp>,
    icons: AppIconLoader,
    selected: Set<String>,
    enabled: Boolean,
    toggle: (String) -> Unit,
) {
    itemsIndexed(listed, key = { _, app -> "app:${app.packageName}" }) { index, app ->
        Surface(
            color = Panel,
            shape =
                RoundedCornerShape(
                    topStart = if (index == 0) 14.dp else 0.dp,
                    topEnd = if (index == 0) 14.dp else 0.dp,
                    bottomStart = if (index == listed.lastIndex) 14.dp else 0.dp,
                    bottomEnd = if (index == listed.lastIndex) 14.dp else 0.dp,
                ),
        ) {
            RoutingApplicationRow(app, icons, app.packageName in selected, enabled) {
                toggle(app.packageName)
            }
        }
    }
}

private fun LazyListScope.manualRoutingItems(
    draft: ClientSettings,
    enabled: Boolean,
    input: ManualPackageInput,
    onChange: (ClientSettings) -> Unit,
    missing: List<String>,
) {
    item(key = "manual") {
        Column(Modifier.padding(top = 12.dp)) {
            SettingsCard {
                SettingField(tr(R.string.package_name), input.text, enabled) {
                    input.change(it.take(255))
                }
                TextButton(
                    {
                        val pkg = input.text.trim()
                        if (!validPackageName(pkg)) input.reject()
                        else {
                            if (pkg !in draft.packages.toSet())
                                onChange(draft.copy(packages = (draft.packages + pkg).take(300)))
                            input.change("")
                        }
                    },
                    enabled = enabled && input.text.isNotBlank(),
                ) {
                    Text(tr(R.string.add_package_name))
                }
            }
        }
    }
    items(missing, key = { "manual:$it" }) { pkg ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("manual-app-$pkg"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                { onChange(draft.copy(packages = draft.packages - pkg)) },
                enabled = enabled,
            ) {
                Text(tr(R.string.remove))
            }
        }
    }
}

// Selected applications first, then the rest that pass the filters.
private fun filtered(apps: List<RoutingApp>, selected: Set<String>, filters: RoutingFilters) =
    apps
        .filter {
            (!filters.selected || it.packageName in selected) &&
                (filters.system || !it.system) &&
                (it.name.contains(filters.query, true) ||
                    it.packageName.contains(filters.query, true))
        }
        .sortedBy { it.packageName !in selected }

// reject() keeps the text for correction and explains in the top-right notice.
private class ManualPackageInput(
    val text: String,
    val change: (String) -> Unit,
    val reject: () -> Unit,
)

// A rescan keeps the current list on screen until the new one arrives.
@Composable
private fun installedRoutingApps(revision: Int, onLoaded: () -> Unit): List<RoutingApp>? {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val loaded by rememberUpdatedState(onLoaded)
    val apps by
        produceState<List<RoutingApp>?>(null, context.applicationContext, configuration, revision) {
            value = withContext(Dispatchers.IO) { readRoutingApps(context) }
            loaded()
        }
    return apps
}

/** A short loading list must not clamp a restored position before apps arrive. */
@Composable
private fun rememberRoutingScroll(loaded: Boolean): LazyListState {
    val state = rememberLazyListState()
    val restored = remember { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    LaunchedEffect(loaded) {
        if (loaded && restored.first > 0) state.scrollToItem(restored.first, restored.second)
    }
    return state
}
