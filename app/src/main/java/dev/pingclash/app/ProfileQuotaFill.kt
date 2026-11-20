// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite card-fill and selection-gleam geometry.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
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
        // The allowance itself; a subscription that gives no total can only say its share.
        quotaAllowance(profile.quota) ?: quotaLabel(remainingFraction(profile.quota)),
        Modifier.testTag("profile-allowance-${profile.id}"),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .86f),
        style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
    )
}

/** Where the card was last drawn, for the frame loop; not state, so writing it redraws nothing. */
private class DrawnSize {
    var size = Size.Zero
}

/**
 * The card as a battery of the data that is left: green, amber below 40 % and red below 20 %. The
 * selected card's fill is alive ([LiquidLight]) while motion is allowed; the others show the same
 * colour, still.
 */
@Composable
internal fun ProfileQuotaFill(
    profile: LocalProfile,
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val fraction = remainingFraction(profile.quota)
    val visual = rememberProfileQuotaMotion(profile, selected)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val level = fraction?.let(::quotaLevel)
    val light =
        remember(level, dark) {
            level?.let { LiquidColors(quotaLevelColor(it, dark), dark, it == QuotaLevel.CRITICAL) }
        }
    // Still when nothing is left to move, and while the app is not on screen: the frames and the
    // accelerometer stop then.
    val alive =
        selected &&
            fraction != null &&
            fraction > 0f &&
            LocalMotionAllowed.current &&
            rememberForeground()
    val drawn = remember { DrawnSize() }
    val liquid = rememberLiquid(profile, alive, visual, drawn)
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
        semantics.clip(UiCorners.Sheet).drawBehind {
            drawn.size = size
            if (light != null) {
                // Until its first frame the light has no size to draw in: show the still one.
                if (alive && liquid.frame != 0L) with(liquid) { drawLiquid(light) }
                else drawStillBattery(visual.amount.value.coerceIn(0f, 1f), light.level, selected)
            }
            if (visual.sweeping) drawGleam(visual.gleam.value)
        }
    ) {
        content()
    }
}

/** The selected card's light, poured in and then stepped every frame while [alive]. */
@Composable
private fun rememberLiquid(
    profile: LocalProfile,
    alive: Boolean,
    visual: ProfileQuotaMotion,
    drawn: DrawnSize,
): LiquidLight {
    val liquid = remember(profile.id) { LiquidLight() }
    val unit = LocalDensity.current.density
    val lean = rememberLean(alive)
    if (alive)
        LaunchedEffect(liquid, unit) {
            liquid.pour()
            // The endless kind of frame: a UI test turns these off, and the card then stays still.
            while (true) withInfiniteAnimationFrameNanos {
                val amount = visual.amount.value.coerceIn(0f, 1f)
                liquid.step(it, drawn.size, amount, unit, lean.value)
            }
        }
    return liquid
}

/** A battery that does not move: vivid, deeper towards its level, with the level marked. */
private fun DrawScope.drawStillBattery(amount: Float, color: Color, selected: Boolean) {
    val level = size.width * amount
    val strength = if (selected) 1.15f else 1f
    clipRect(right = level) {
        drawRect(
            Brush.horizontalGradient(
                listOf(
                    color.copy(alpha = .42f * strength),
                    color.copy(alpha = .56f * strength),
                    color.copy(alpha = (.74f * strength).coerceAtMost(1f)),
                ),
                endX = level.coerceAtLeast(1f),
            )
        )
    }
    drawLine(
        Color.White.copy(alpha = if (selected) .9f else .55f),
        Offset(level, 0f),
        Offset(level, size.height),
        density,
    )
}

private fun DrawScope.drawGleam(position: Float) {
    val x = size.width * position
    val width = 90.dp.toPx()
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = .30f), Color.Transparent),
            start = Offset(x - width, 0f),
            end = Offset(x + width, size.height),
        )
    )
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
