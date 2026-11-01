// SPDX-License-Identifier: GPL-3.0-only
// Color literals, type sizes and corner metrics are the design token definitions.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color
    @Composable get() = MaterialTheme.colorScheme.surface
internal val Mint: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Success: Color
    @Composable get() = successColor(MaterialTheme.colorScheme.background.luminance() < .5f)
internal val Warning: Color
    @Composable get() = warningColor(MaterialTheme.colorScheme.background.luminance() < .5f)

internal fun successColor(dark: Boolean) = if (dark) Color(0xFF98D4B1) else Color(0xFF246447)

internal fun warningColor(dark: Boolean) = if (dark) Color(0xFFFFD54F) else Color(0xFF806400)

internal object UiCorners {
    val Compact = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(14.dp)
    val Sheet = RoundedCornerShape(24.dp)
    val Pill = RoundedCornerShape(50)
}

internal fun oceanColors(dark: Boolean): ColorScheme =
    if (dark)
        darkColorScheme(
            primary = Color(0xFFA0D1D3),
            onPrimary = Color(0xFF12383E),
            primaryContainer = Color(0xFF24474D),
            onPrimaryContainer = Color(0xFFD4EEEE),
            background = Color(0xFF101D23),
            surface = Color(0xFF18292F),
            surfaceVariant = Color(0xFF24383E),
            onBackground = Color(0xFFE7F1F0),
            onSurface = Color(0xFFE7F1F0),
            onSurfaceVariant = Color(0xFFB0C2C4),
            outline = Color(0xFF8B9FA2),
            outlineVariant = Color(0xFF354A50),
            error = Color(0xFFF3B4B9),
            onError = Color(0xFF591C26),
            errorContainer = Color(0xFF492730),
            onErrorContainer = Color(0xFFFFDADF),
            inversePrimary = Color(0xFF20616C),
            secondary = Color(0xFFB5C7CA),
            onSecondary = Color(0xFF20363C),
            secondaryContainer = Color(0xFF2C4147),
            onSecondaryContainer = Color(0xFFDDE9E9),
            tertiary = Color(0xFFCCC8B7),
            onTertiary = Color(0xFF35352B),
            tertiaryContainer = Color(0xFF43483F),
            onTertiaryContainer = Color(0xFFE6E7D7),
            surfaceTint = Color.Transparent,
            inverseSurface = Color(0xFFE4EFED),
            inverseOnSurface = Color(0xFF22383E),
            surfaceDim = Color(0xFF101D23),
            surfaceBright = Color(0xFF304249),
            surfaceContainerLowest = Color(0xFF0D181D),
            surfaceContainerLow = Color(0xFF15252B),
            surfaceContainer = Color(0xFF18292F),
            surfaceContainerHigh = Color(0xFF20333A),
            surfaceContainerHighest = Color(0xFF283D44),
        )
    else
        lightColorScheme(
            primary = Color(0xFF20616C),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCEDEC),
            onPrimaryContainer = Color(0xFF173F47),
            background = Color(0xFFF3F7F6),
            surface = Color(0xFFFCFEFD),
            surfaceVariant = Color(0xFFE9F0EF),
            onBackground = Color(0xFF182F37),
            onSurface = Color(0xFF182F37),
            onSurfaceVariant = Color(0xFF52676E),
            outline = Color(0xFF72878B),
            outlineVariant = Color(0xFFD7E2E1),
            error = Color(0xFFA63142),
            onError = Color.White,
            errorContainer = Color(0xFFF8E3E5),
            onErrorContainer = Color(0xFF792336),
            inversePrimary = Color(0xFFA0D1D3),
            secondary = Color(0xFF526970),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5EEED),
            onSecondaryContainer = Color(0xFF263F47),
            tertiary = Color(0xFF626650),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEAECDD),
            onTertiaryContainer = Color(0xFF333B28),
            surfaceTint = Color.Transparent,
            inverseSurface = Color(0xFF243A41),
            inverseOnSurface = Color(0xFFE7F1F0),
            surfaceDim = Color(0xFFD9E4E2),
            surfaceBright = Color(0xFFFCFEFD),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF6FAF8),
            surfaceContainer = Color(0xFFEDF3F1),
            surfaceContainerHigh = Color(0xFFE6EEEB),
            surfaceContainerHighest = Color(0xFFDFE9E6),
        )

private fun uiType(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = height.sp,
        fontFeatureSettings = "tnum",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PingClashTheme(
    mode: String = "system",
    // Use the built-in palette unless the user explicitly enables system colours.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = mode == "dark" || mode == "system" && isSystemInDarkTheme()
    val colors =
        if (dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        } else oceanColors(dark)
    val typography =
        Typography(
            displayLarge = uiType(32, 40, FontWeight.SemiBold),
            displayMedium = uiType(32, 40, FontWeight.SemiBold),
            displaySmall = uiType(32, 40, FontWeight.SemiBold),
            headlineLarge = uiType(32, 40, FontWeight.SemiBold),
            headlineMedium = uiType(22, 30, FontWeight.SemiBold),
            headlineSmall = uiType(22, 30, FontWeight.SemiBold),
            titleLarge = uiType(22, 30, FontWeight.SemiBold),
            titleMedium = uiType(16, 24, FontWeight.Medium),
            titleSmall = uiType(14, 20, FontWeight.Medium),
            bodyLarge = uiType(16, 24),
            bodyMedium = uiType(14, 20),
            bodySmall = uiType(12, 18),
            labelLarge = uiType(14, 20, FontWeight.Medium),
            labelMedium = uiType(12, 18, FontWeight.Medium),
            labelSmall = uiType(12, 18),
        )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes =
            Shapes(
                UiCorners.Compact,
                UiCorners.Compact,
                UiCorners.Card,
                UiCorners.Sheet,
                UiCorners.Sheet,
            ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalRippleConfiguration provides null,
            LocalIndication provides NoPressIndication,
            content = content,
        )
    }
}
