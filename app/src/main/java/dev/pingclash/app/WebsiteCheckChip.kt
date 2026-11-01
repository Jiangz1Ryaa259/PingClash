// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Native rendering of the supplied CallChip interaction.

package dev.pingclash.app

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class CheckPhase {
    IDLE,
    RUNNING,
    DONE,
    ERROR,
}

private val ChipShape = RoundedCornerShape(12.dp)

@Composable
internal fun WebsiteCheckChip(
    target: ServiceTarget,
    result: Latency?,
    enabled: Boolean,
    retry: () -> Unit,
) {
    val phase = chipPhase(result)
    val motion = LocalMotionAllowed.current
    val fraction = remember { Animatable(0f) }
    val wash = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    ChipMotion(result, motion, fraction, wash, shake)
    val tone =
        when (phase) {
            CheckPhase.DONE -> Success
            CheckPhase.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
    val shakePixels = with(LocalDensity.current) { 2.dp.toPx() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        shape = ChipShape,
        border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.fillMaxWidth()
                .testTag("service-detail-${target.id}")
                .graphicsLayer { translationX = shake.value * shakePixels }
                .fluidClickable(
                    enabled && phase != CheckPhase.RUNNING,
                    depth = .97f,
                    onClick = retry,
                )
                .semantics {
                    contentDescription = tr(R.string.website_retry, target.name)
                    stateDescription =
                        when (result) {
                            is Latency.Measured -> "${result.elapsed} ms"
                            is Latency.Failed -> websiteFailureText(result)
                            Latency.Running -> tr(R.string.ui_checking)
                            null -> tr(R.string.ui_not_measured)
                        }
                },
    ) {
        ChipBody(
            target,
            result,
            tone,
            motion,
            Modifier.chipFill(tone, phase, { fraction.value }, { wash.value }),
        )
    }
}

@Composable
private fun ChipMotion(
    result: Latency?,
    motion: Boolean,
    fraction: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    wash: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    shake: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
) {
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(result, motion) {
        val animate = mounted && motion
        mounted = true
        shake.snapTo(0f)
        when (result) {
            Latency.Running -> {
                wash.snapTo(0f)
                fraction.snapTo(0f)
                if (motion) fraction.animateTo(.9f, tween(2500, easing = LinearEasing))
                else fraction.snapTo(.9f)
            }
            is Latency.Measured -> {
                if (animate) {
                    wash.snapTo(1f)
                    fraction.animateTo(1f, tween(180))
                    delay(200)
                    wash.animateTo(0f, tween(380, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)))
                } else {
                    fraction.snapTo(1f)
                    wash.snapTo(0f)
                }
            }
            is Latency.Failed -> {
                wash.snapTo(0f)
                if (animate)
                    for (x in listOf(-1f, 1f, -.66f, .66f, -.33f, 0f)) shake.animateTo(x, tween(55))
            }
            null -> {
                fraction.snapTo(0f)
                wash.snapTo(0f)
            }
        }
    }
}

private fun Modifier.chipFill(
    tone: Color,
    phase: CheckPhase,
    fraction: () -> Float,
    wash: () -> Float,
) = drawWithCache {
    onDrawBehind {
        val amount = fraction()
        val height = if (phase == CheckPhase.DONE) size.height * wash() else size.height
        drawRect(
            tone.copy(alpha = if (phase == CheckPhase.RUNNING) .08f else .12f),
            topLeft = Offset(0f, size.height - height),
            size = Size(size.width * amount.coerceIn(0f, 1f), height),
        )
        if (phase == CheckPhase.ERROR) drawRect(tone.copy(alpha = .035f))
    }
}

@Composable
private fun ChipGlyph(phase: CheckPhase, tone: Color, motion: Boolean) {
    AnimatedContent(
        phase,
        Modifier.size(18.dp).clipToBounds(),
        transitionSpec = {
            if (!motion) EnterTransition.None togetherWith ExitTransition.None
            else
                (slideInVertically(tween(200)) { it } + fadeIn(tween(140))).togetherWith(
                    slideOutVertically(tween(160)) { -it } + fadeOut(tween(100))
                )
        },
        contentAlignment = Alignment.Center,
        label = "website-chip-glyph",
    ) { shown ->
        if (shown == CheckPhase.RUNNING)
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = tone)
        else
            AppGlyph(
                when (shown) {
                    CheckPhase.DONE -> PcIcon.Check
                    CheckPhase.ERROR -> PcIcon.Refresh
                    else -> PcIcon.Globe
                },
                Modifier.size(16.dp),
                tone,
            )
    }
}

@Composable
private fun ChipTiming(result: Latency?) {
    var elapsed by remember(result == Latency.Running) { mutableLongStateOf(0) }
    LaunchedEffect(result) {
        if (result == Latency.Running) {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                elapsed = SystemClock.elapsedRealtime() - start
                delay(100)
            }
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        val label =
            when (result) {
                Latency.Running -> "$elapsed ms"
                is Latency.Measured -> "${result.elapsed} ms"
                is Latency.Failed -> websiteFailureText(result)
                null -> tr(R.string.ui_not_measured)
            }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color =
                when (result) {
                    is Latency.Measured -> latencyColor(result.elapsed)
                    is Latency.Failed -> MaterialTheme.colorScheme.error
                    else -> Muted
                },
            maxLines = 1,
        )
        if (result is Latency.Failed && result.elapsed > 0)
            Text(
                "${result.elapsed} ms",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = Muted,
            )
    }
}

private fun chipPhase(result: Latency?) =
    when (result) {
        Latency.Running -> CheckPhase.RUNNING
        is Latency.Measured -> CheckPhase.DONE
        is Latency.Failed -> CheckPhase.ERROR
        null -> CheckPhase.IDLE
    }

@Composable
private fun ChipBody(
    target: ServiceTarget,
    result: Latency?,
    tone: Color,
    motion: Boolean,
    modifier: Modifier,
) {
    val phase = chipPhase(result)
    Row(
        modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChipGlyph(phase, tone, motion)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                target.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                remember(target.url) { URL(target.url).host },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChipTiming(result)
    }
}
