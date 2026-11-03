// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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

/**
 * The add button unfolds a menu above it, origami-style: one strip per way to add a profile,
 * unfolding one after another from the button upward. Unfolded, the strips meet as one rounded card
 * in three groups (links, text and files, QR codes). No modal sheet or input-blocking scrim.
 */
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
    Column(modifier.widthIn(max = 300.dp), horizontalAlignment = Alignment.End) {
        if (present)
            ImportMethod.entries.forEachIndexed { index, method ->
                OrigamiPanel(index, method, expanded, !locked && expanded) {
                    expanded = false
                    onImport(method)
                }
            }
        Spacer(Modifier.height(if (expanded) 10.dp else 0.dp))
        ExtendedFloatingActionButton(
            onClick = { if (!locked) expanded = !expanded },
            expanded = !compact || expanded,
            icon = { AppGlyph(if (expanded) PcIcon.Minus else PcIcon.Add) },
            text = { Text(tr(if (expanded) R.string.cancel else R.string.add_subscription)) },
            modifier =
                Modifier.testTag("profile-add").semantics {
                    contentDescription = tr(R.string.add_subscription)
                    if (locked) disabled()
                },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Paper-strip origami: strips unfold one after another from the button upward, alternating crease
 * direction, with a slight overshoot and a crease shadow that lifts as each one opens. The first
 * strip of a group carries the grey band that separates it from the group above.
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
    val methods = ImportMethod.entries
    val order = methods.size - 1 - index
    val unfold by
        animateFloatAsState(
            if (entered) 1f else 0f,
            if (!motion) snap()
            else if (entered) tween(320, delayMillis = order * 45, easing = EaseOutBack)
            else tween(180, delayMillis = index * 22, easing = FastOutLinearInEasing),
            label = "import-fold",
        )
    val crease = if (order % 2 == 0) -1f else 1f
    val radius = 16.dp
    val shape =
        RoundedCornerShape(
            topStart = if (index == 0) radius else 0.dp,
            topEnd = if (index == 0) radius else 0.dp,
            bottomStart = if (index == methods.lastIndex) radius else 0.dp,
            bottomEnd = if (index == methods.lastIndex) radius else 0.dp,
        )
    val groupStart = index > 0 && methods[index - 1].section != method.section
    Surface(
        shape = shape,
        color = Panel.copy(alpha = .98f),
        modifier =
            Modifier.width(264.dp).graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f)
                cameraDistance = 14 * density
                rotationX = crease * 88f * (1f - unfold)
                alpha = unfold.coerceIn(0f, 1f)
                translationY = (1f - unfold).coerceAtLeast(0f) * size.height
            },
    ) {
        Column(
            Modifier.drawWithContent {
                drawContent()
                val shade = (1f - unfold).coerceIn(0f, 1f) * .32f
                if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
            }
        ) {
            // A thicker band between groups, thin lines within one, as in iOS menus.
            if (groupStart)
                Spacer(
                    Modifier.fillMaxWidth()
                        .height(7.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                )
            else if (index > 0)
                HorizontalDivider(
                    thickness = .5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            ImportMenuRow(method, enabled, onClick)
        }
    }
}

@Composable
private fun ImportMenuRow(method: ImportMethod, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(48.dp)
            .fluidClickable(enabled, depth = .98f, onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag("import-method-${method.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr(method.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            AppGlyph(method.glyph, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurface)
        }
    }
}
