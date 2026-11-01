// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite card-fill and selection-gleam geometry.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun remainingFraction(quota: SubscriptionQuota?): Float? =
    quota
        ?.total
        ?.takeIf { it > 0 }
        ?.let { total ->
            quota.used?.let { used ->
                (1.0 - used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
        }

internal fun quotaRefilled(before: SubscriptionQuota?, after: SubscriptionQuota?): Boolean =
    before?.remaining?.let { old -> after?.remaining?.let { it > old } == true } == true

@Stable
internal class ProfileQuotaMotion(start: Float) {
    val amount = Animatable(start)
    val gleam = Animatable(2f)
    var refilling by mutableStateOf(false)
        private set

    var sweeping by mutableStateOf(false)
        private set

    suspend fun update(target: Float, animate: Boolean, refill: Boolean, selectedNow: Boolean) {
        refilling = animate && refill
        sweeping = animate && (refill || selectedNow)
        if (refilling) amount.snapTo(0f)
        coroutineScope {
            launch {
                if (animate) amount.animateTo(target, tween(if (refill) 1100 else 350))
                else amount.snapTo(target)
            }
            launch {
                if (sweeping) {
                    gleam.snapTo(-.4f)
                    gleam.animateTo(1.4f, tween(1000))
                } else gleam.snapTo(2f)
            }
        }
        refilling = false
        sweeping = false
    }
}

@Composable
internal fun rememberProfileQuotaMotion(
    profile: LocalProfile,
    selected: Boolean,
): ProfileQuotaMotion {
    val quota = profile.quota
    val fraction = remainingFraction(quota) ?: 0f
    val visual = remember(profile.id) { ProfileQuotaMotion(fraction) }
    var previous by rememberSaveable(profile.id) { mutableStateOf(quota?.json()?.toString()) }
    var wasSelected by rememberSaveable(profile.id) { mutableStateOf(selected) }
    val motion = LocalMotionAllowed.current
    LaunchedEffect(quota, selected, motion) {
        val before = previous?.let { SubscriptionQuota.read(org.json.JSONObject(it)) }
        val refill = quotaRefilled(before, quota)
        val selectedNow = selected && !wasSelected
        previous = quota?.json()?.toString()
        wasSelected = selected
        visual.update(fraction, motion, refill, selectedNow)
    }
    return visual
}

private fun quotaLabel(fraction: Float?) =
    fraction?.let { tr(R.string.profile_remaining_percent, (it * 100).toInt()) }
        ?: tr(R.string.profile_quota_unknown)

@Composable
internal fun ProfileQuotaLabel(profile: LocalProfile) {
    Text(
        quotaLabel(remainingFraction(profile.quota)),
        color = Muted,
        style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
    )
}

@Composable
internal fun ProfileQuotaFill(
    profile: LocalProfile,
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val fraction = remainingFraction(profile.quota)
    val visual = rememberProfileQuotaMotion(profile, selected)
    val accent = MaterialTheme.colorScheme.primary
    val label = quotaLabel(fraction)
    val semantics =
        if (profile.quota != null)
            Modifier.testTag("profile-quota-${profile.id}").semantics {
                contentDescription = label
                if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                stateDescription =
                    if (visual.refilling) tr(R.string.profile_quota_refilling) else label
            }
        else Modifier
    Box(
        semantics.clip(UiCorners.Sheet).drawWithCache {
            val fill =
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = if (selected) .27f else .15f),
                        accent.copy(alpha = if (selected) .18f else .09f),
                    )
                )
            onDrawBehind {
                if (fraction != null)
                    clipRect(right = size.width * visual.amount.value.coerceIn(0f, 1f)) {
                        drawRect(fill)
                    }
                if (visual.sweeping) {
                    val x = size.width * visual.gleam.value
                    val width = 90.dp.toPx()
                    drawRect(
                        Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = .30f),
                                Color.Transparent,
                            ),
                            start = Offset(x - width, 0f),
                            end = Offset(x + width, size.height),
                        )
                    )
                }
            }
        }
    ) {
        content()
    }
}

@Composable
internal fun ProfileInventory(profile: LocalProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            tr(
                R.string.profile_inventory,
                profile.nodes.size,
                profile.groups.size,
                profile.rules.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        profile.nodes
            .groupingBy { it.type }
            .eachCount()
            .toSortedMap()
            .forEach { (type, count) ->
                Text(
                    tr(R.string.profile_protocol_count, type, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        Text(
            tr(if (profile.source == null) R.string.local_import else R.string.subscription),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}
