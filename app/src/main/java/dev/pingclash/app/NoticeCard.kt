// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Compact banner geometry and perimeter stroke.

package dev.pingclash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val BannerShape = RoundedCornerShape(22.dp)
internal val NoticeFuseFraction = SemanticsPropertyKey<Float>("NoticeFuseFraction")

/** A compact pill: yellow fuse for a timed reminder, a fixed red rim for a warning. */
@Composable
internal fun NoticeBanner(
    notice: IssueNotice?,
    center: IssueNoticeCenter,
    remaining: () -> Float,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionAllowed.current
    AnimatedContent(
        notice,
        modifier,
        transitionSpec = {
            if (!motion) EnterTransition.None togetherWith ExitTransition.None
            else
                (fadeIn(tween(140)) + scaleIn(tween(180), .96f, TransformOrigin(1f, 0f)))
                    .togetherWith(
                        fadeOut(tween(110)) + scaleOut(tween(110), .98f, TransformOrigin(1f, 0f))
                    )
                    .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.TopEnd,
        label = "notice-banner",
        contentKey = { it?.id },
    ) { shown ->
        if (shown != null)
            NoticeCard(
                shown,
                center.entries.size,
                { center.inspect(shown) },
                { center.dismiss(shown.id) },
                { if (shown.id == notice?.id) remaining() else 0f },
            )
    }
}

@Composable
private fun NoticeCard(
    notice: IssueNotice,
    count: Int,
    open: () -> Unit,
    dismiss: () -> Unit,
    remaining: () -> Float,
) {
    var displacement by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shift by
        animateFloatAsState(
            displacement,
            if (dragging || !LocalMotionAllowed.current) snap() else spring(.82f, 750f),
            label = "notice-drag",
        )
    val threshold = with(LocalDensity.current) { 76.dp.toPx() }
    val windowWidth =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val width = minOf(260.dp, (windowWidth - 24.dp).coerceAtLeast(0.dp))
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val warning = notice.severity == IssueSeverity.WARNING
    val tint =
        if (warning) (if (dark) Color(0xffff8a90) else Color(0xffd23b47)) else Color(0xffefb432)
    val fill = if (dark) Color(0xff1f2b31) else Color(0xfffbfcfd)
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.width(width)
            .graphicsLayer { translationX = shift }
            .swipeAway(
                threshold,
                { dragging = it },
                { displacement = it },
                { displacement },
                dismiss,
            )
    ) {
        Surface(
            onClick = open,
            shape = BannerShape,
            color = fill,
            interactionSource = press,
            modifier =
                Modifier.fillMaxWidth()
                    .shadow(8.dp, BannerShape)
                    .fluidPress(press, .97f, dim = false)
                    .noticeFuse(tint, warning, remaining)
                    .testTag(notice.tag)
                    .semantics {
                        this[NoticeFuseFraction] = if (warning) 1f else remaining()
                        customActions =
                            listOf(
                                CustomAccessibilityAction(tr(R.string.issue_dismiss)) {
                                    dismiss()
                                    true
                                }
                            )
                    },
        ) {
            NoticeContent(notice, count, tint, warning)
        }
    }
}

@Composable
private fun NoticeContent(notice: IssueNotice, count: Int, tint: Color, warning: Boolean) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 9.dp).heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(26.dp).background(tint.copy(alpha = .12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppGlyph(WARNING_GLYPH, Modifier.size(16.dp), tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                notice.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (warning) issueStageTitle(notice.stage) else notice.detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 1)
            Text("+${count - 1}", style = MaterialTheme.typography.labelSmall, color = Muted)
        AppGlyph(PcIcon.ChevronRight, Modifier.size(12.dp), Muted)
    }
}

/** Geometry is cached; only the segment length is read each frame. Starts at twelve o'clock. */
private fun Modifier.noticeFuse(tint: Color, warning: Boolean, remaining: () -> Float) =
    drawWithCache {
        val stroke = 1.5.dp.toPx()
        val edge = stroke / 2f
        val rim = noticeRim(size.width, size.height, edge, 22.dp.toPx())
        val measure = PathMeasure().apply { setPath(rim, false) }
        val segment = Path()
        val pen = Stroke(stroke, cap = StrokeCap.Round)
        onDrawWithContent {
            drawContent()
            drawPath(rim, tint.copy(alpha = .12f), style = pen)
            val fraction = if (warning) 1f else remaining().coerceIn(0f, 1f)
            segment.reset()
            if (measure.length > 0f && fraction > 0f) {
                measure.getSegment(measure.length * (1f - fraction), measure.length, segment, true)
                drawPath(segment, tint, style = pen)
            }
        }
    }

private fun noticeRim(width: Float, height: Float, edge: Float, corner: Float): Path {
    val right = (width - edge).coerceAtLeast(edge)
    val bottom = (height - edge).coerceAtLeast(edge)
    val r = minOf(corner, (right - edge) / 2f, (bottom - edge) / 2f).coerceAtLeast(0f)
    return Path().apply {
        moveTo(width / 2f, edge)
        lineTo(right - r, edge)
        arcTo(Rect(right - 2 * r, edge, right, edge + 2 * r), -90f, 90f, false)
        lineTo(right, bottom - r)
        arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
        lineTo(edge + r, bottom)
        arcTo(Rect(edge, bottom - 2 * r, edge + 2 * r, bottom), 90f, 90f, false)
        lineTo(edge, edge + r)
        arcTo(Rect(edge, edge, edge + 2 * r, edge + 2 * r), 180f, 90f, false)
        lineTo(width / 2f, edge)
        close()
    }
}

private fun Modifier.swipeAway(
    threshold: Float,
    onDragging: (Boolean) -> Unit,
    onMove: (Float) -> Unit,
    current: () -> Float,
    dismiss: () -> Unit,
) =
    pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { onDragging(true) },
            onDragCancel = {
                onDragging(false)
                onMove(0f)
            },
            onDragEnd = {
                onDragging(false)
                if (abs(current()) >= threshold) dismiss() else onMove(0f)
            },
        ) { change, amount ->
            change.consume()
            onMove(current() + amount)
        }
    }
