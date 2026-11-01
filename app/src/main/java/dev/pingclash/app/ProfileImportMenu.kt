// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Accordion panels unfold from the FAB upward; no modal sheet or input-blocking scrim. */
@Composable
internal fun ProfileImportMenu(
    compact: Boolean,
    locked: Boolean,
    modifier: Modifier,
    onImport: (ImportMethod) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    val motion = LocalMotionAllowed.current
    LaunchedEffect(expanded, motion) {
        if (expanded) {
            present = true
        } else {
            if (motion) delay(400)
            present = false
        }
    }
    androidx.activity.compose.BackHandler(expanded) { expanded = false }
    LaunchedEffect(locked) { if (locked) expanded = false }
    Column(
        modifier.widthIn(max = 300.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (present)
            ImportMethod.entries.forEachIndexed { index, method ->
                OrigamiPanel(index, method, expanded, !locked && expanded) {
                    expanded = false
                    onImport(method)
                }
            }
        Spacer(Modifier.height(if (expanded) 8.dp else 0.dp))
        ExtendedFloatingActionButton(
            onClick = { if (!locked) expanded = !expanded },
            expanded = !compact || expanded,
            icon = { AppGlyph(if (expanded) BACK_GLYPH else PcIcon.Add) },
            text = { Text(tr(if (expanded) R.string.cancel else R.string.add_subscription)) },
            modifier =
                Modifier.testTag("profile-add").semantics {
                    contentDescription = tr(R.string.add_subscription)
                    if (locked) disabled()
                },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

/**
 * Paper-strip origami: panels unfold one after another from the button upward, alternating crease
 * direction, with a slight overshoot and a crease shadow that lifts as each panel opens.
 */
@Composable
private fun OrigamiPanel(
    index: Int,
    method: ImportMethod,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val motion = LocalMotionAllowed.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { entered = expanded }
    val order = ImportMethod.entries.size - 1 - index
    val unfold by
        animateFloatAsState(
            if (entered) 1f else 0f,
            if (!motion) snap()
            else if (entered) tween(320, delayMillis = order * 45, easing = EaseOutBack)
            else tween(180, delayMillis = index * 22, easing = FastOutLinearInEasing),
            label = "import-fold",
        )
    val crease = if (order % 2 == 0) -1f else 1f
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = UiCorners.Compact,
        color = Panel.copy(alpha = .98f),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.width(248.dp)
                .height(48.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(.5f, 1f)
                    cameraDistance = 14 * density
                    rotationX = crease * 88f * (1f - unfold)
                    alpha = unfold.coerceIn(0f, 1f)
                    translationY = (1f - unfold).coerceAtLeast(0f) * size.height
                }
                .drawWithContent {
                    drawContent()
                    val shade = (1f - unfold).coerceIn(0f, 1f) * .32f
                    if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
                }
                .testTag("import-method-${method.name.lowercase()}"),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppGlyph(method.glyph, Modifier.size(20.dp))
            Text(tr(method.title), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
