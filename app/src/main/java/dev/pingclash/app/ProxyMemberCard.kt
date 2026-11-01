// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val PROXY_SELECTION_MILLIS = 180
private const val SELECTED_MEMBER_TINT = .36f

internal data class ProxyMemberPresentation(
    val name: String,
    val description: String,
    val chosen: Boolean,
    val selectable: Boolean,
    val delay: ProxyDelay?,
    val list: Boolean,
)

internal class ProxyMemberActions(
    val select: () -> Unit,
    val details: () -> Unit,
    val test: (() -> Unit)?,
    val open: (() -> Unit)?,
)

@Composable
internal fun ProxyMemberCard(
    state: ProxyMemberPresentation,
    actions: ProxyMemberActions,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    val background by
        animateColorAsState(
            if (state.chosen)
                lerp(Panel, MaterialTheme.colorScheme.primaryContainer, SELECTED_MEMBER_TINT)
            else Panel,
            if (LocalMotionAllowed.current) tween(PROXY_SELECTION_MILLIS) else snap(),
            label = "proxy-selection",
        )
    Surface(
        shape = UiCorners.Card,
        color = background,
        border =
            BorderStroke(
                if (state.chosen) 1.dp else .7.dp,
                if (state.chosen) Mint.copy(alpha = .55f)
                else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { selected = state.chosen }
                .fluidPress(press, depth = .97f)
                .combinedClickable(
                    interactionSource = press,
                    indication = null,
                    role = Role.RadioButton,
                    onLongClickLabel = tr(R.string.proxy_member_details),
                    onLongClick = actions.details,
                    onClick = { if (state.selectable) actions.select() else actions.details() },
                ),
    ) {
        if (state.list) ProxyMemberRow(state, actions) else ProxyMemberTile(state, actions)
    }
}

@Composable
private fun ProxyMemberTile(state: ProxyMemberPresentation, actions: ProxyMemberActions) {
    Column(
        Modifier.heightIn(min = 112.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MemberName(state, Modifier.weight(1f).heightIn(min = 42.dp))
            if (state.chosen) MemberCheck()
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MemberDescription(state.name, state.description, actions.open, Modifier.weight(1f))
            ProxyMemberLatency(state.name, state.delay, actions.test)
        }
    }
}

@Composable
private fun ProxyMemberRow(state: ProxyMemberPresentation, actions: ProxyMemberActions) {
    Row(
        Modifier.heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MemberName(state, Modifier.fillMaxWidth())
            MemberDescription(state.name, state.description, actions.open, Modifier.fillMaxWidth())
        }
        ProxyMemberLatency(state.name, state.delay, actions.test)
        if (state.chosen) MemberCheck() else Spacer(Modifier.size(18.dp))
    }
}

@Composable
private fun MemberName(state: ProxyMemberPresentation, modifier: Modifier) {
    Text(
        state.name,
        modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = if (state.selectable) MaterialTheme.colorScheme.onSurface else Muted,
    )
}

@Composable
private fun MemberDescription(
    name: String,
    description: String,
    open: (() -> Unit)?,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            description,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (open != null)
            IconButton(
                open,
                Modifier.size(32.dp).testTag("proxy-open-$name").semantics {
                    contentDescription = tr(R.string.open_policy_group, name)
                },
            ) {
                ProxySymbol("right", Modifier.size(15.dp))
            }
    }
}

@Composable
private fun MemberCheck() {
    Box(Modifier.size(18.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
        ProxySymbol("check", Modifier.size(12.dp), MaterialTheme.colorScheme.onPrimary)
    }
}
