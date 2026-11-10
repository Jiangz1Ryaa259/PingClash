// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun PageHeading(
    title: String,
    modifier: Modifier = Modifier,
    tag: String = "page-heading",
) {
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            title,
            Modifier.testTag(tag).semantics { heading() },
            color = pageInk().text,
            style = MaterialTheme.typography.headlineMedium.onPage(),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun SettingsSlide(
    page: String,
    backProgress: Float = 0f,
    content: @Composable (String) -> Unit,
) {
    val motion = LocalMotionAllowed.current
    AnimatedContent(
        page,
        modifier =
            Modifier.clipToBounds().graphicsLayer {
                val progress = if (motion) backProgress.coerceIn(0f, 1f) else 0f
                translationX = size.width * progress * .35f
                scaleX = 1f - progress * .05f
                scaleY = 1f - progress * .05f
                alpha = 1f - progress * .3f
            },
        transitionSpec = {
            if (!motion) fadeIn(snap()) togetherWith fadeOut(snap())
            else {
                val enter = if (targetState.isNotEmpty()) 1 else -1
                (slideInHorizontally(tween(220)) { it * enter } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it * enter / 4 } + fadeOut(tween(140)))
            }
        },
        label = "settings-detail",
    ) {
        content(it)
    }
}
