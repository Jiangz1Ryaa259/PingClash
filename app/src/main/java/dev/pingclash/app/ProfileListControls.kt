// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Profile list controls: header actions, drag reordering and the fetch lock.

/**
 * Lock for actions that only download and hot-apply (import, update, providers). They stay
 * available while the VPN runs; null falls back to the general lock.
 */
internal val LocalProfileFetchLock = compositionLocalOf<Boolean?> { null }
internal val LocalProfileSelectionLock = compositionLocalOf<Boolean?> { null }

@Composable internal fun fetchLocked(locked: Boolean) = LocalProfileFetchLock.current ?: locked

@Composable
internal fun selectionLocked(locked: Boolean) = LocalProfileSelectionLock.current ?: locked

internal fun sortActions(
    reorder: ProfileReorder,
    profile: LocalProfile,
    commit: (List<String>) -> Unit,
) =
    listOf(R.string.move_profile_up to -1, R.string.move_profile_down to 1).map { (label, step) ->
        CustomAccessibilityAction(tr(label)) { reorder.step(profile.id, step)?.let(commit) != null }
    }

/** Top-right: update every subscription at once, and toggle reordering. */
@Composable
internal fun ProfileHeaderActions(
    canRefresh: Boolean,
    canSort: Boolean,
    sorting: Boolean,
    actions: ProfileActions,
    onSort: () -> Unit,
) {
    actions.refreshAll?.let { refreshAll ->
        IconButton(
            refreshAll,
            enabled = canRefresh && !sorting,
            modifier =
                Modifier.testTag("profiles-refresh-all").semantics {
                    contentDescription = tr(R.string.update_all_subscriptions)
                },
        ) {
            if (LocalRefreshingProfiles.current.isEmpty()) AppGlyph(PcIcon.Refresh)
            else SpinningGlyph(PcIcon.Refresh)
        }
    }
    if (actions.reorder != null)
        IconButton(
            onSort,
            enabled = canSort,
            modifier =
                Modifier.testTag("profiles-sort").semantics {
                    contentDescription =
                        tr(if (sorting) R.string.sort_profiles_done else R.string.sort_profiles)
                },
        ) {
            AppGlyph(
                if (sorting) PcIcon.Check else PcIcon.Sort,
                tint = if (sorting) Mint else LocalContentColor.current,
            )
        }
}

@Composable
internal fun ProfileDragHandle(
    profile: LocalProfile,
    reorder: ProfileReorder,
    commit: (List<String>) -> Unit,
) {
    Box(
        Modifier.size(48.dp)
            .testTag("profile-drag-${profile.id}")
            .reorderable(reorder, profile.id, commit, immediate = true),
        contentAlignment = Alignment.Center,
    ) {
        AppGlyph(PcIcon.Grip, tint = Muted)
    }
}

private const val FULL_TURN_DEGREES = 360f
private const val SPIN_MILLIS = 900

/** Profiles downloading now; drives the header spinner and per-card progress. */
internal val LocalRefreshingProfiles = compositionLocalOf { emptySet<String>() }

@Composable
private fun SpinningGlyph(icon: PcIcon) {
    val angle =
        if (LocalMotionAllowed.current) {
            val turn = rememberInfiniteTransition(label = "refresh-spin")
            turn.animateFloat(
                0f,
                FULL_TURN_DEGREES,
                infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)),
                label = "refresh-angle",
            )
        } else null
    AppGlyph(icon, Modifier.size(24.dp).graphicsLayer { rotationZ = angle?.value ?: 0f })
}

/** Takes the menu button's place (same 48dp target) while the profile updates. */
@Composable
internal fun ProfileUpdatingSpinner(profile: LocalProfile) {
    val label = tr(R.string.profile_updating)
    Box(
        Modifier.size(48.dp).testTag("profile-updating-${profile.id}").semantics {
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Mint, strokeWidth = 2.dp)
    }
}
