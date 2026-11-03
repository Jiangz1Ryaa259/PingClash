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

/**
 * Apple-style neutrals: black text, grey secondary text and a blue accent for actions. The blues
 * are a shade deeper (light) and lighter (dark) than Apple's defaults to reach 4.5:1 contrast.
 */
internal fun oceanColors(dark: Boolean): ColorScheme =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF409CFF),
            onPrimary = Color(0xFF001D3D),
            primaryContainer = Color(0xFF0B3460),
            onPrimaryContainer = Color(0xFFD6E8FF),
            background = Color(0xFF000000),
            surface = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFF2C2C2E),
            onBackground = Color(0xFFF2F2F7),
            onSurface = Color(0xFFF2F2F7),
            onSurfaceVariant = Color(0xFFAEAEB2),
            outline = Color(0xFF8E8E93),
            outlineVariant = Color(0xFF38383A),
            error = Color(0xFFFF6961),
            onError = Color.White,
            errorContainer = Color(0xFF4A1C1A),
            onErrorContainer = Color(0xFFFFDAD6),
            inversePrimary = Color(0xFF0068D9),
            secondary = Color(0xFFAEAEB2),
            onSecondary = Color(0xFF1C1C1E),
            secondaryContainer = Color(0xFF3A3A3C),
            onSecondaryContainer = Color(0xFFF2F2F7),
            tertiary = Color(0xFFBF5AF2),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFF3B2050),
            onTertiaryContainer = Color(0xFFF1DBFF),
            surfaceTint = Color.Transparent,
            inverseSurface = Color(0xFFF2F2F7),
            inverseOnSurface = Color(0xFF1C1C1E),
            surfaceDim = Color(0xFF000000),
            surfaceBright = Color(0xFF3A3A3C),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF151517),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF242426),
            surfaceContainerHighest = Color(0xFF2C2C2E),
        )
    else
        lightColorScheme(
            primary = Color(0xFF0068D9),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE3EEFF),
            onPrimaryContainer = Color(0xFF00316B),
            background = Color(0xFFF2F2F7),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFEFF4),
            onBackground = Color(0xFF1C1C1E),
            onSurface = Color(0xFF1C1C1E),
            onSurfaceVariant = Color(0xFF636366),
            outline = Color(0xFF7C7C80),
            outlineVariant = Color(0xFFE5E5EA),
            error = Color(0xFFD70015),
            onError = Color.White,
            errorContainer = Color(0xFFFFE5E4),
            onErrorContainer = Color(0xFF7A0010),
            inversePrimary = Color(0xFF409CFF),
            secondary = Color(0xFF636366),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5E5EA),
            onSecondaryContainer = Color(0xFF1C1C1E),
            tertiary = Color(0xFFAF52DE),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFF3E5FB),
            onTertiaryContainer = Color(0xFF4A1A62),
            surfaceTint = Color.Transparent,
            inverseSurface = Color(0xFF1C1C1E),
            inverseOnSurface = Color(0xFFF2F2F7),
            surfaceDim = Color(0xFFE5E5EA),
            surfaceBright = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F7FA),
            surfaceContainer = Color(0xFFF2F2F7),
            surfaceContainerHigh = Color(0xFFEBEBF0),
            surfaceContainerHighest = Color(0xFFE5E5EA),
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
