// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun InputIssue(message: String) {
    val offset = remember { Animatable(0f) }
    val moving = LocalMotionAllowed.current
    val density = LocalDensity.current.density
    LaunchedEffect(message) {
        if (moving) for (x in listOf(-7f, 7f, -4f, 4f, 0f)) offset.animateTo(x, tween(48))
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = UiCorners.Compact,
        modifier =
            Modifier.fillMaxWidth()
                .animateContentSize()
                .graphicsLayer { translationX = offset.value * density }
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
