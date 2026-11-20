// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable

/** What every opened group's sheet shares: the model and the list options. */
internal class GroupSheetHost(
    val model: AppViewModel,
    val sort: MutableState<ProxySort>,
    val hideFailed: MutableState<Boolean>,
)

/** One opened group's sheet, with its own list position; [close] returns to the one below. */
@Composable
internal fun StackedGroupSheet(
    state: ProxyPageState,
    group: LocalGroup,
    actions: ProxyPageActions,
    host: GroupSheetHost,
    close: () -> Unit,
) {
    val memberList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    GroupSheet(
        state,
        group,
        actions,
        GroupSheetOptions(
            host.sort,
            host.hideFailed,
            memberList,
            SheetClose(back = close, dismiss = close),
            tcpTest = { group ->
                // A nested group is measured through the node it leads to now.
                val exits =
                    group.proxies
                        .filter { it in state.groups }
                        .mapNotNull { m -> state.profile.exitOf(m, state.live)?.let { m to it } }
                        .toMap()
                host.model.testTcpProxies(state.profile, group.proxies, group.name, exits)
            },
            makeMain = { host.model.selectMainGroup(state.profile, it) },
        ),
    )
}
