// SPDX-License-Identifier: GPL-3.0-only
// Colours and the brightness threshold are visual design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Bitmap
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.core.graphics.scale

/**
 * Colours for what sits directly on the page's background rather than on a card: titles, section
 * labels, links and icons, where the background may not match the theme.
 */
internal class PageInk(
    val text: Color,
    val muted: Color,
    val accent: Color,
    val danger: Color,
    val shadow: Shadow?,
)

private val InkOnDark =
    PageInk(
        text = Color.White,
        muted = Color(0xFFEBEBF5),
        accent = Color(0xFF9CCEFF),
        danger = Color(0xFFFFA59E),
        shadow = Shadow(Color.Black.copy(alpha = .5f), Offset(0f, 1f), 8f),
    )

private val InkOnBright =
    PageInk(
        text = Color(0xFF1C1C1E),
        muted = Color(0xFF3C3C43),
        accent = Color(0xFF0052AD),
        danger = Color(0xFFA80012),
        shadow = Shadow(Color.White.copy(alpha = .6f), Offset.Zero, 8f),
    )

/** Null while the page sits on a plain theme or solid background. */
internal val LocalPageInk = staticCompositionLocalOf<PageInk?> { null }

/** From this version [OceanBackdrop] draws [RealtimeOcean] rather than the photos. */
private const val LIVE_SCENE_SDK = 33

/** Where dark text and white text read equally well (WCAG contrast against #1C1C1E and white). */
private const val EVEN_CONTRAST_LUMINANCE = .2f

/**
 * The ink for a scene, or null where the theme's own colours already read. The live scene
 * ([RealtimeOcean], Android 13+) is a bright day in the light theme and a dark night in the dark
 * one, so it matches the theme. Older versions show dark photos in both themes. [pictureLuminance]
 * is how bright the user's picture looks under its dim layer; without a picture the page shows the
 * plain theme background.
 */
internal fun sceneInk(
    scene: String,
    pictureLuminance: Float?,
    liveScene: Boolean = android.os.Build.VERSION.SDK_INT >= LIVE_SCENE_SDK,
): PageInk? =
    when (scene) {
        "ocean",
        "clouds" -> if (liveScene) null else InkOnDark
        "image" ->
            pictureLuminance?.let { if (it < EVEN_CONTRAST_LUMINANCE) InkOnDark else InkOnBright }
        else -> null
    }

/** How bright a picture looks under its [scrim], from the average of its pixels. */
internal fun pictureLuminance(picture: Bitmap, scrim: Color): Float {
    val small = picture.scale(SAMPLES, SAMPLES)
    var red = 0
    var green = 0
    var blue = 0
    for (y in 0 until SAMPLES) for (x in 0 until SAMPLES) {
        val pixel = small.getPixel(x, y)
        red += pixel shr 16 and 0xFF
        green += pixel shr 8 and 0xFF
        blue += pixel and 0xFF
    }
    val count = SAMPLES * SAMPLES * 255f
    return scrim.compositeOver(Color(red / count, green / count, blue / count)).luminance()
}

private const val SAMPLES = 16

internal fun android.view.Window.setDarkSystemBarIcons(dark: Boolean) {
    androidx.core.view.WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = dark
        isAppearanceLightNavigationBars = dark
    }
}

/** The current page's colours for text and icons that are not on a card. */
@Composable
internal fun pageInk(): PageInk =
    LocalPageInk.current
        ?: PageInk(
            MaterialTheme.colorScheme.onSurface,
            Muted,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.error,
            null,
        )

/** The style's shadow on a photo, which keeps text readable over bright patches. */
@Composable internal fun TextStyle.onPage(): TextStyle = copy(shadow = pageInk().shadow)

/** Default-coloured text and icons inside [content] sit on the background, not on a card. */
@Composable
internal fun OnPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides pageInk().text, content = content)
}

/** Chips on the background carry the same card colour as the rows around them. */
@Composable
internal fun pageChipColors() =
    FilterChipDefaults.filterChipColors(
        containerColor = Panel.copy(alpha = if (LocalImmersiveScene.current) .9f else 1f)
    )
