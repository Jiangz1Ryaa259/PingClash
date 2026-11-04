// SPDX-License-Identifier: GPL-3.0-only
// Sidebar geometry is a deliberate design value.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val SIDEBAR_EXPANDED = 248.dp
internal val SIDEBAR_COLLAPSED = 76.dp

private val ROW = 52.dp
private val HEADER = 56.dp
private val STATUS = 48.dp
private val INNER = 10.dp

/** Icons sit in this column in both states, so collapsing never moves them. */
private val ICON_COLUMN = SIDEBAR_COLLAPSED - INNER * 2
private val TAB_ICONS = listOf(PcIcon.Gauge, PcIcon.Layers, PcIcon.Profile, PcIcon.Settings)

/** Square, but corner-based: the optical lens rejects any other kind of shape. */
private val SIDEBAR_SHAPE = RoundedCornerShape(0.dp)

/**
 * The landscape navigation: a glass sidebar docked to the start edge, the full height of the
 * screen, with a hairline where it meets the page. It holds the app name, the four pages as rows of
 * icon and label, and the connection at the foot, and collapses to its icon column. The selection
 * is the bottom bar's glass lens, travelling down the rows; drag it to switch.
 */
@Composable
internal fun GlassSidebar(
    selected: Int,
    width: Dp,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val dark = Ink.luminance() < .5f
    val style = LocalNavigationGlassStyle.current ?: navigationGlassStyle(dark)
    val backdrop = LocalNavigationBackdrop.current
    val material = if (backdrop != null) rememberLayerBackdrop() else null
    val blur = backdrop != null || LocalNavHaze.current != null
    // 0 collapsed, 1 expanded; labels follow the width as it springs.
    val open =
        ((width - SIDEBAR_COLLAPSED) / (SIDEBAR_EXPANDED - SIDEBAR_COLLAPSED)).coerceIn(0f, 1f)
    val edge = if (dark) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .08f)
    // The glass also fills the space under the status bar and any start inset (cutout,
    // side navigation bar); only the contents keep clear of them.
    Box(
        modifier.fillMaxHeight().testTag("glass-navigation").semantics {
            this[NavBlurEnabled] = blur
            this[NavOpticalBackend] = if (backdrop != null) "backdrop" else "haze"
        }
    ) {
        // The glass is its own layer beside the contents, as in the bottom bar: the lens below
        // samples it, so the lens must not be inside the layer it samples.
        Box(
            Modifier.matchParentSize()
                .then(if (material != null) Modifier.layerBackdrop(material) else Modifier)
                .navGlassMaterial(style, SIDEBAR_SHAPE)
                .drawBehind {
                    drawRect(
                        edge,
                        topLeft = Offset(size.width - 1.dp.toPx(), 0f),
                        size = Size(1.dp.toPx(), size.height),
                    )
                }
        )
        BoxWithConstraints(
            Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Vertical
                    )
                )
                .width(width)
                .fillMaxHeight()
        ) {
            val roomForStatus = maxHeight >= HEADER + ROW * 4 + STATUS + INNER * 2 + 8.dp
            Column(Modifier.fillMaxSize().padding(INNER)) {
                SidebarHeader(open, dark, onToggle)
                Spacer(Modifier.height(8.dp))
                SidebarTabs(selected, open, dark, material, onSelect)
                Spacer(Modifier.weight(1f))
                if (roomForStatus) SidebarStatus(open, dark)
            }
        }
    }
}

@Composable
private fun SidebarHeader(open: Float, dark: Boolean, onToggle: () -> Unit) {
    val label = tr(if (open < .5f) R.string.ui_sidebar_expand else R.string.ui_sidebar_collapse)
    Row(Modifier.fillMaxWidth().height(HEADER), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .fluidClickable(depth = .9f, role = Role.Button, onClick = onToggle)
                    .semantics { contentDescription = label }
                    .testTag("sidebar-toggle"),
                contentAlignment = Alignment.Center,
            ) {
                SidebarGlyph(navLabelColor(dark))
            }
        }
        Text(
            "PingClash",
            Modifier.graphicsLayer { alpha = open },
            color = navLabelColor(dark),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * The four rows, the lens over the chosen one and, on Android 13+, a hidden accent copy of the rows
 * the lens samples so its row reads in the accent colour.
 */
@Composable
private fun SidebarTabs(
    selected: Int,
    open: Float,
    dark: Boolean,
    material: com.kyant.backdrop.backdrops.LayerBackdrop?,
    onSelect: (Int) -> Unit,
) {
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val haptic = LocalHapticFeedback.current
    val drag = remember { NavigationDragState() }
    val presses = remember { List(4) { MutableInteractionSource() } }
    val motion = rememberNavigationMotion(drag, selected, LocalMotionAllowed.current)
    val style = LocalNavigationGlassStyle.current ?: navigationGlassStyle(dark)
    val accents = if (material != null) rememberLayerBackdrop() else null
    val source =
        if (material != null && accents != null) rememberCombinedBackdrop(material, accents)
        else null
    val row = with(LocalDensity.current) { ROW.toPx() }
    val labels =
        listOf(
            tr(R.string.home),
            tr(R.string.proxies),
            tr(R.string.profiles),
            tr(R.string.settings),
        )
    val choose: (Int) -> Unit = {
        if (it != current) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            select(it)
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .height(ROW * 4)
            .navigationDrag(
                NavigationDragGeometry(row, false, current, vertical = true),
                drag,
                choose,
            )
    ) {
        val tabs = SidebarTabContent(labels, selected, presses)
        // Without the optical backend the chosen row itself turns accent.
        SidebarRows(tabs, open, dark, if (accents == null) selected else NO_ACCENT, choose)
        if (accents != null)
            Box(
                Modifier.fillMaxSize()
                    .clearAndSetSemantics {}
                    .graphicsLayer { alpha = 0f }
                    .layerBackdrop(accents)
                    .navSelectionWash(dark, motion::optics)
            ) {
                SidebarRows(tabs, open, dark, ALL_ACCENT, null)
            }
        Box(
            Modifier.align(Alignment.TopStart)
                .absoluteOffset(y = 2.dp)
                .fillMaxWidth()
                .height(ROW - 4.dp)
                .navSelectorMaterial(style, source, motion, vertical = true) {
                    motion.visualPosition() * row
                }
                .testTag("glass-selector")
        )
    }
}

private const val NO_ACCENT = -1
private const val ALL_ACCENT = -2

private class SidebarTabContent(
    val labels: List<String>,
    val selected: Int,
    val presses: List<MutableInteractionSource>,
)

/** The rows in one tint; [accent] is the row drawn in the accent colour, or all, or none. */
@Composable
private fun SidebarRows(
    tabs: SidebarTabContent,
    open: Float,
    dark: Boolean,
    accent: Int,
    onSelect: ((Int) -> Unit)?,
) {
    val presses = tabs.presses
    Column(Modifier.fillMaxSize()) {
        tabs.labels.forEachIndexed { index, label ->
            val tint =
                if (accent == ALL_ACCENT || accent == index) navAccentColor(dark)
                else navLabelColor(dark)
            val action =
                if (onSelect != null)
                    Modifier.testTag("nav-$index")
                        .semantics { contentDescription = label }
                        .selectable(
                            tabs.selected == index,
                            interactionSource = presses[index],
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                else Modifier
            Row(
                Modifier.fillMaxWidth().height(ROW).then(action).fluidPress(presses[index], .97f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
                    NavigationGlyph(TAB_ICONS[index], Modifier.size(23.dp), tint)
                }
                Text(
                    label,
                    Modifier.graphicsLayer { alpha = open }.clearAndSetSemantics {},
                    color = tint,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** The connection at the foot: a coloured dot, and its state while expanded. */
@Composable
private fun SidebarStatus(open: Float, dark: Boolean) {
    val vpn by VpnRuntime.state.collectAsState()
    val color =
        when (vpn.stage) {
            VpnPhase.ESTABLISHED -> Success
            VpnPhase.ERROR -> MaterialTheme.colorScheme.error
            VpnPhase.CONNECTING,
            VpnPhase.STOPPING -> Color(0xFFFF9F0A)
            else -> Muted
        }
    Row(
        Modifier.fillMaxWidth().height(STATUS).testTag("sidebar-status"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Text(
            shortcutStatus(vpn),
            Modifier.graphicsLayer { alpha = open },
            color = navLabelColor(dark).copy(alpha = .72f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/** A window with a sidebar pane: the toggle's glyph. */
@Composable
private fun SidebarGlyph(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val line = 1.8.dp.toPx()
        val inset = line / 2 + 1.dp.toPx()
        drawRoundRect(
            tint,
            topLeft = Offset(inset, inset + 2.dp.toPx()),
            size = Size(size.width - inset * 2, size.height - inset * 2 - 4.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(line),
        )
        val x = size.width * .38f
        drawLine(
            tint,
            Offset(x, inset + 2.dp.toPx()),
            Offset(x, size.height - inset - 2.dp.toPx()),
            line,
        )
    }
}
