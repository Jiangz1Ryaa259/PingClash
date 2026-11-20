// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Finite sizes, springs and tints of the overlays.

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Frosted glass for what floats over a page (menus, dialogs, sheets). Their own windows cannot see
 * the scene, so this is the nearly solid frosted colour with the light rim and a deeper shadow.
 */
@Composable
private fun Modifier.floatingGlass(shape: Shape, elevation: androidx.compose.ui.unit.Dp): Modifier {
    val dark = glassDark()
    val surface = MaterialTheme.colorScheme.surface
    return shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(surface.copy(alpha = if (dark) .98f else .985f))
        .drawBehind {
            drawPath(
                Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
                },
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) .30f else 1f),
                    .45f to Color.White.copy(alpha = 0f),
                ),
                style = Stroke(1.dp.toPx()),
            )
        }
}

/**
 * A dialog as a glass card that springs in over the dimmed page. Takes the same parts as the
 * Material dialog it replaces: title, text and up to two actions, the confirming one last.
 */
@Suppress("LongParameterList") // The same parameters as the Material component it replaces.
@Composable
internal fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val motion = LocalMotionAllowed.current
    val grow = remember { Animatable(if (motion) .9f else 1f) }
    val fade = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            kotlinx.coroutines.coroutineScope {
                launch { grow.animateTo(1f, spring(.62f, 520f)) }
                launch { fade.animateTo(1f, tween(140)) }
            }
        }
    }
    Dialog(
        onDismissRequest,
        DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = grow.value
                    scaleY = grow.value
                    alpha = fade.value
                }
                .floatingGlass(GlassCorner, 24.dp)
                .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionLocalProvider(LocalSceneBackdrop provides null) {
                DialogParts(title, text, dismissButton, confirmButton)
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)

/** A bottom sheet of frosted glass with a rounder top and a light rim; springs up as before. */
@Suppress("LongParameterList") // The same parameters as the Material component it replaces.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    dragHandle: (@Composable () -> Unit)? = { SheetHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .28f),
        dragHandle = null,
        properties = properties,
    ) {
        CompositionLocalProvider(LocalSceneBackdrop provides null) {
            Column(Modifier.fillMaxWidth().floatingGlass(SheetShape, 0.dp)) {
                dragHandle?.invoke()
                content()
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp, 5.dp)
                .clip(UiCorners.Pill)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .22f))
        )
    }
}

/** A dialog's title, text and actions, in the Material dialog's styles. */
@Composable
private fun DialogParts(
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    title?.let {
        CompositionLocalProvider(
            LocalContentColor provides ink,
            LocalTextStyle provides MaterialTheme.typography.titleLarge,
        ) {
            Box(Modifier.padding(end = 8.dp)) { it() }
        }
    }
    text?.let {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            LocalTextStyle provides MaterialTheme.typography.bodyMedium,
        ) {
            Box(Modifier.padding(end = 8.dp)) { it() }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
    ) {
        dismissButton?.invoke()
        confirmButton()
    }
}
