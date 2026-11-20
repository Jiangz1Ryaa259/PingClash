// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite sizes, springs and timings of one effect.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.hypot
import kotlin.math.roundToInt

private val MENU_CORNER = 24.dp
private val MENU_MARGIN = 12.dp

/** The control's coordinates, kept without being state, so reading them never recomposes. */
internal class ControlPlace {
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    fun bounds(): Rect = coordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero
}

/** How far the menu has opened, and a running count that gives each item its turn. */
internal class MenuReveal(val progress: () -> Float) {
    var items = 0
}

internal val LocalMenuReveal = compositionLocalOf<MenuReveal?> { null }

/** Where the system bars are, so a menu never opens under them. */
private class MenuRoom(val top: Int, val bottom: Int)

/**
 * The app's menu: a solid card beside the control that opened it, uncovered by a circle spreading
 * from the control's centre while its items rise in one after another; closing draws the circle
 * back into the control, faster. The page behind is neither dimmed nor blurred. Call it inside the
 * same box as the control, as a drop-down list was.
 */
@Composable
internal fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Where the control is, read once as the menu opens: tracking it on every layout let the
    // menu's own window feed back into it and redraw without end.
    val control = remember { ControlPlace() }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.size(0.dp).onGloballyPositioned {
            control.coordinates = it.parentLayoutCoordinates
        }
    )
    val density = LocalDensity.current
    val bars = WindowInsets.safeDrawing
    val room = MenuRoom(bars.getTop(density), bars.getBottom(density))
    val motion = LocalMotionAllowed.current
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, motion) {
        if (expanded) {
            anchor = control.bounds()
            shown = true
            closing = false
            if (motion) progress.animateTo(1f, spring(.95f, 260f)) else progress.snapTo(1f)
        } else if (shown) {
            closing = true
            if (motion) progress.animateTo(0f, spring(1f, 520f))
            progress.snapTo(0f)
            shown = false
        }
    }
    if (!shown) return
    Dialog(
        onDismissRequest,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        // Opening, the items only rise; closing, they go as the circle draws back.
        val reveal = remember {
            var peak = 0f
            MenuReveal {
                val now = progress.value
                if (closing) now.also { peak = it } else maxOf(peak, now).also { peak = it }
            }
        }
        reveal.items = 0
        val catcher = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().clickable(catcher, indication = null, onClick = onDismissRequest)
        )
        CompositionLocalProvider(
            LocalMenuReveal provides reveal,
            LocalSceneBackdrop provides null,
        ) {
            RevealPanel(anchor, room, { progress.value }, modifier, content)
        }
    }
}

/**
 * Lays the card out beside the control, then shows only the part inside a circle around the
 * control's centre whose radius grows with [progress] until it holds the whole card.
 */
@Composable
private fun RevealPanel(
    anchor: Rect,
    room: MenuRoom,
    progress: () -> Float,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val margin = with(density) { MENU_MARGIN.roundToPx() }
    val corner = with(density) { MENU_CORNER.toPx() }
    val lift = with(density) { 16.dp.toPx() }
    val shape = RoundedCornerShape(MENU_CORNER)
    // Solid: the menu's window cannot blur the page, so a see-through fill would show the page's
    // text sharp behind the items. In the dark a faint edge keeps it apart from dark cards.
    val edge =
        if (glassDark())
            Modifier.border(.5.dp, MaterialTheme.colorScheme.onSurface.copy(.12f), shape)
        else Modifier
    Layout(
        content = {
            Column(
                modifier
                    .widthIn(min = 220.dp, max = 300.dp)
                    .width(IntrinsicSize.Max)
                    .background(MaterialTheme.colorScheme.surface, shape)
                    .then(edge)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                content = content,
            )
        }
    ) { measurables, constraints ->
        val top = room.top + margin
        val bottom = constraints.maxHeight - room.bottom - margin
        val limit =
            Constraints(
                maxWidth = constraints.maxWidth,
                maxHeight = (bottom - top).coerceAtLeast(0),
            )
        val menu = measurables.single().measure(limit)
        val target =
            revealBounds(
                anchor,
                Size(menu.width.toFloat(), menu.height.toFloat()),
                Rect(
                    margin.toFloat(),
                    top.toFloat(),
                    (constraints.maxWidth - margin).toFloat(),
                    bottom.toFloat(),
                ),
                layoutDirection == LayoutDirection.Rtl,
            )
        val origin = Offset(anchor.center.x - target.left, anchor.center.y - target.top)
        layout(constraints.maxWidth, constraints.maxHeight) {
            menu.placeWithLayer(target.left.roundToInt(), target.top.roundToInt()) {
                revealLayer(origin, progress().coerceAtLeast(0f), corner, lift)
            }
        }
    }
}

/** The card at [p]: cut to the circle, settling from a little smaller, its shadow coming in. */
private fun GraphicsLayerScope.revealLayer(origin: Offset, p: Float, corner: Float, lift: Float) {
    val open = p.coerceAtMost(1f)
    shape = RevealShape(origin, p, corner)
    clip = true
    val settle = .965f + .035f * open
    scaleX = settle
    scaleY = settle
    // A card not measured yet has no size: keep the default origin rather than divide by zero.
    if (size.width > 0f && size.height > 0f)
        transformOrigin = TransformOrigin(origin.x / size.width, origin.y / size.height)
    shadowElevation = lift * open
    alpha = if (p > .001f) 1f else 0f
}

/**
 * The card's place: under the control, its end edge on the control's (its start edge, right to
 * left); above the control when there is no room below; always inside [area], the window less its
 * margins and bars.
 */
private fun revealBounds(anchor: Rect, menu: Size, area: Rect, rtl: Boolean): Rect {
    val left =
        (if (rtl) anchor.left else anchor.right - menu.width).coerceIn(
            area.left,
            (area.right - menu.width).coerceAtLeast(area.left),
        )
    val top =
        when {
            anchor.bottom + menu.height <= area.bottom -> anchor.bottom
            anchor.top - menu.height >= area.top -> anchor.top - menu.height
            else -> (area.bottom - menu.height).coerceAtLeast(area.top)
        }
    return Rect(Offset(left, top), menu)
}

/**
 * The card's rounded rectangle cut to a circle around [origin]; at [progress] 1 the circle reaches
 * the farthest corner, so the card is whole.
 */
private data class RevealShape(val origin: Offset, val progress: Float, val corner: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val card = RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner))
        if (progress >= 1f) return Outline.Rounded(card)
        val far =
            maxOf(
                hypot(origin.x, origin.y),
                hypot(size.width - origin.x, origin.y),
                hypot(origin.x, size.height - origin.y),
                hypot(size.width - origin.x, size.height - origin.y),
            ) + 2f
        val circle = Path().apply { addOval(Rect(origin, far * progress)) }
        val rounded = Path().apply { addRoundRect(card) }
        return Outline.Generic(Path.combine(PathOperation.Intersect, circle, rounded))
    }
}

/** One choice of a [GlassMenu]: an optional icon and its label; rises in on its turn. */
@Composable
internal fun GlassMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val reveal = LocalMenuReveal.current
    val turn = remember { reveal?.let { it.items++ } ?: 0 }
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    Row(
        modifier
            .graphicsLayer {
                val shown = reveal?.let { itemShown(it.progress(), turn) } ?: 1f
                alpha = shown
                translationY = (1f - shown) * rise
            }
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassClickable(enabled, Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides ink.copy(alpha = if (enabled) 1f else .38f),
            LocalTextStyle provides MaterialTheme.typography.bodyLarge,
        ) {
            leadingIcon?.let {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { it() }
            }
            text()
        }
    }
}

/**
 * How far one item has risen in: each starts a little after the one above, the delays capped so
 * that every item, however long the menu, is fully shown once the panel has opened.
 */
internal fun itemShown(progress: Float, turn: Int): Float {
    if (progress >= 1f) return 1f
    val delay = (turn * .05f).coerceAtMost(.3f)
    return ((progress - .25f - delay) / .4f).coerceIn(0f, 1f)
}
