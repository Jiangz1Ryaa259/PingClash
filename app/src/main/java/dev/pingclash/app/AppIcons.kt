// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Original, local vectors: one optical grid, rounded terminals and joins.
// Icons are decorative; their parent control owns the accessible action label.
internal val WARNING_GLYPH = PcIcon.Warning
internal val GLYPH_COPY = PcIcon.Copy
internal val GLYPH_SUN = PcIcon.Sun
internal val GLYPH_MOON = PcIcon.Moon

internal val BACK_GLYPH = PcIcon.Back

private object AppIcons {
    private fun gear(): String {
        val points =
            (0 until 32).map { i ->
                val angle = (i - .5) * Math.PI / 16
                val radius = if (i % 4 in 1..2) 9.6 else 7.7
                Pair((12 + radius * cos(angle)).toFloat(), (12 + radius * sin(angle)).toFloat())
            }
        fun midpoint(a: Pair<Float, Float>, b: Pair<Float, Float>) =
            "${(a.first+b.first)/2},${(a.second+b.second)/2}"
        return buildString {
            append("M${midpoint(points.last(),points.first())}")
            points.forEachIndexed { i, p ->
                append(" Q${p.first},${p.second} ${midpoint(p,points[(i+1)%points.size])}")
            }
            append(" Z M15.2,12 A3.2,3.2 0 1,1 8.8,12 A3.2,3.2 0 1,1 15.2,12")
        }
    }

    private val paths =
        mapOf(
            0 to
                "M12 3.2 V11 M6.8 6.1 C4.8 7.6 3.7 9.8 3.7 12.3 A8.3 8.3 0 0 0 20.3 12.3 C20.3 9.8 19.2 7.6 17.2 6.1",
            GLYPH_COPY.vectorId to
                "M8 8 H18 A2 2 0 0 1 20 10 V20 H10 A2 2 0 0 1 8 18 Z M16 8 V4 H4 V16 H8",
            GLYPH_SUN.vectorId to
                ("M16 12 A4 4 0 1 1 8 12 A4 4 0 1 1 16 12 M12 2 V4 M12 20 V22 M2 12 H4 M20 12 H22 " +
                    "M5 5 L6.5 6.5 M17.5 17.5 L19 19 M5 19 L6.5 17.5 M17.5 6.5 L19 5"),
            GLYPH_MOON.vectorId to "M20.2 14.3 A8.5 8.5 0 1 1 9.7 3.8 A7 7 0 0 0 20.2 14.3 Z",
            1 to
                ("M20.5 12 A8.5 8.5 0 1 1 3.5 12 A8.5 8.5 0 1 1 20.5 12 Z M3.8 12 H20.2 M12 3.5 C8 " +
                    "7.8 8 16.2 12 20.5 C16 16.2 16 7.8 12 3.5"),
            2 to
                "M3 12 H6.5 L9.2 6.2 Q9.7 5.2 10.1 6.4 L13.8 17.6 Q14.2 18.8 14.7 17.6 L17.5 12 H21",
            3 to gear(),
            4 to "M5 12 H19 M12 5 V19",
            5 to "M9 6.5 L14.5 12 L9 17.5",
            6 to
                ("M12 3 L19.5 6 V11.2 C19.5 15.8 16.7 19.1 12 21 C7.3 19.1 4.5 15.8 4.5 11.2 V6 Z " +
                    "M8.5 11.8 L11 14.3 L15.7 9.6"),
            7 to
                ("M14.2 3.5 H7 A2 2 0 0 0 5 5.5 V18.5 A2 2 0 0 0 7 20.5 H17 A2 2 0 0 0 19 18.5 " +
                    "V8.3 Z M14 3.8 V7 A1.5 1.5 0 0 0 15.5 8.5 H18.6 M8.8 12 H15.2 M8.8 15.8 H13.2"),
            8 to
                "M17.2 10.5 A6.7 6.7 0 1 1 3.8 10.5 A6.7 6.7 0 1 1 17.2 10.5 Z M15.4 15.4 L20.2 20.2",
            PcIcon.Terminal.vectorId to "M3 4 H21 V20 H3 Z M7 8 L11 12 L7 16 M13 16 H17",
            9 to
                "M12 3.5 V15 M7.5 10.5 L12 15 L16.5 10.5 M4 17 V18.5 A2 2 0 0 0 6 20.5 H18 A2 2 0 0 0 20 18.5 V17",
            12 to
                ("M4.5 17.8 A9 9 0 1 1 19.5 17.8 Q19 18.5 18 18.5 H6 Q5 18.5 4.5 17.8 Z M12 12.5 " +
                    "L16 8.5 M12.9 13 A1.3 1.3 0 1 1 10.3 13 A1.3 1.3 0 1 1 12.9 13 M5.8 12 H6.8 M8.2 " +
                    "6.8 L8.7 7.6 M17.3 12 H18.3"),
            13 to
                ("M8 6.5 V5 A2 2 0 0 1 10 3 H14 A2 2 0 0 1 16 5 V6.5 M5 6.5 H19 A2 2 0 0 1 21 8.5 " +
                    "V18.5 A2 2 0 0 1 19 20.5 H5 A2 2 0 0 1 3 18.5 V8.5 A2 2 0 0 1 5 6.5 Z M3.5 12 " +
                    "H20.5 M10 12 V14 H14 V12"),
            14 to
                "M12 3.5 L21 8.3 L12 13.1 L3 8.3 Z M3 12 L12 16.8 L21 12 M3 15.8 L12 20.5 L21 15.8",
            15 to "M5.5 12 L9.8 16.3 L18.5 7.6",
            16 to "M13.3 3 L5.2 13.3 H11 L10.7 21 L18.8 10.7 H13 Z",
            17 to "M4 6.5 H20 M4 12 H20 M4 17.5 H20 M8 4.5 V8.5 M16 10 V14 M10 15.5 V19.5",
            18 to
                "M20.5 12 A8.5 8.5 0 1 1 3.5 12 A8.5 8.5 0 1 1 20.5 12 Z M12 3.5 V20.5 M15 6.5 V17.5 M18 9 V15",
            19 to "M4 4 V18 A2 2 0 0 0 6 20 H21 M8 15 V11 M12.5 15 V6 M17 15 V9",
            20 to
                ("M9.2 14.8 L14.8 9.2 M8.2 8 L9.8 6.4 A4.1 4.1 0 0 1 17.6 14.2 L16 15.8 M8 8.2 " +
                    "L6.4 9.8 A4.1 4.1 0 0 0 14.2 17.6 L15.8 16"),
            21 to
                ("M4.5 4 H19.5 A1.5 1.5 0 0 1 21 5.5 V9 A1.5 1.5 0 0 1 19.5 10.5 H4.5 A1.5 1.5 0 0 " +
                    "1 3 9 V5.5 A1.5 1.5 0 0 1 4.5 4 Z M4.5 13.5 H19.5 A1.5 1.5 0 0 1 21 15 V18.5 " +
                    "A1.5 1.5 0 0 1 19.5 20 H4.5 A1.5 1.5 0 0 1 3 18.5 V15 A1.5 1.5 0 0 1 4.5 13.5 Z " +
                    "M6.5 7.3 H6.6 M6.5 16.8 H6.6 M15.5 7.3 H17.5 M15.5 16.8 H17.5"),
            22 to
                ("M3.5 8.2 C8.2 3.5 15.8 3.5 20.5 8.2 M6.8 11.5 C9.7 8.6 14.3 8.6 17.2 11.5 M10 " +
                    "14.7 C11.1 13.6 12.9 13.6 14 14.7 M12 18.2 V18.3"),
            WARNING_GLYPH.vectorId to
                "M20.5 12 A8.5 8.5 0 1 1 3.5 12 A8.5 8.5 0 1 1 20.5 12 Z M12 7 V12.5 M12 16 V16.1",
            23 to
                ("M9 3.5 H15 A1 1 0 0 1 16 4.5 V8.5 A1 1 0 0 1 15 9.5 H9 A1 1 0 0 1 8 8.5 V4.5 A1 " +
                    "1 0 0 1 9 3.5 Z M12 9.5 V13.5 M5 16 V14.5 A1 1 0 0 1 6 13.5 H18 A1 1 0 0 1 19 " +
                    "14.5 V16 M3 16 H7 V20.5 H3 Z M17 16 H21 V20.5 H17 Z"),
            24 to
                "M20.5 12 A8.5 8.5 0 1 1 3.5 12 A8.5 8.5 0 1 1 20.5 12 Z M12 10.5 V16.5 M12 7.2 V7.3",
            25 to "M6.5 9 L12 14.5 L17.5 9",
            26 to "M12 5 V5.1 M12 12 V12.1 M12 19 V19.1",
            BACK_GLYPH.vectorId to "M15.5 5 L8.5 12 L15.5 19",
            PcIcon.Refresh.vectorId to "M20 12 A8 8 0 1 1 17.66 6.34 M18.2 2.8 V6.9 H14.1",
            PcIcon.Minus.vectorId to "M5 12 H19",
            PcIcon.Clipboard.vectorId to
                ("M9 4.5 H7 A2 2 0 0 0 5 6.5 V19 A2 2 0 0 0 7 21 H17 A2 2 0 0 0 19 19 V6.5 A2 2 0 " +
                    "0 0 17 4.5 H15 M9.5 3 H14.5 A0.5 0.5 0 0 1 15 3.5 V5.5 A0.5 0.5 0 0 1 14.5 6 " +
                    "H9.5 A0.5 0.5 0 0 1 9 5.5 V3.5 A0.5 0.5 0 0 1 9.5 3 Z"),
            PcIcon.Folder.vectorId to
                ("M3.5 7 A2 2 0 0 1 5.5 5 H9.5 L11.5 7 H18.5 A2 2 0 0 1 20.5 9 V17.5 A2 2 0 0 1 " +
                    "18.5 19.5 H5.5 A2 2 0 0 1 3.5 17.5 Z"),
            PcIcon.Archive.vectorId to
                ("M4.5 4.5 H19.5 A1 1 0 0 1 20.5 5.5 V7.5 A1 1 0 0 1 19.5 8.5 H4.5 A1 1 0 0 1 3.5 " +
                    "7.5 V5.5 A1 1 0 0 1 4.5 4.5 Z M5 8.5 V18.5 A2 2 0 0 0 7 20.5 H17 A2 2 0 0 0 19 " +
                    "18.5 V8.5 M10 12.5 H14"),
            PcIcon.Qr.vectorId to
                ("M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M7 7 V7.1 M17 7 V7.1 " +
                    "M7 17 V17.1 M14 14 H16.5 V16.5 M20 14 V14.1 M14 20 V20.1 M17.5 17.5 H20 V20 " +
                    "H17.5 Z"),
            PcIcon.Camera.vectorId to
                ("M4 8.5 A2 2 0 0 1 6 6.5 H8 L9.5 4.5 H14.5 L16 6.5 H18 A2 2 0 0 1 20 8.5 V17.5 " +
                    "A2 2 0 0 1 18 19.5 H6 A2 2 0 0 1 4 17.5 Z M15.5 13 A3.5 3.5 0 1 1 8.5 13 A3.5 " +
                    "3.5 0 1 1 15.5 13"),
            PcIcon.Eye.vectorId to
                ("M2.5 12 C5 7.2 8.4 5 12 5 C15.6 5 19 7.2 21.5 12 C19 16.8 15.6 19 12 19 C8.4 19 " +
                    "5 16.8 2.5 12 Z M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12"),
            PcIcon.EyeOff.vectorId to
                ("M2.5 12 C5 7.2 8.4 5 12 5 C15.6 5 19 7.2 21.5 12 C19 16.8 15.6 19 12 19 C8.4 19 " +
                    "5 16.8 2.5 12 Z M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12 M4 4 L20 20"),
            PcIcon.Grip.vectorId to "M5 8.5 H19 M5 12 H19 M5 15.5 H19",
            PcIcon.Sort.vectorId to
                "M8 19 V5 M4.5 8.5 L8 5 L11.5 8.5 M16 5 V19 M12.5 15.5 L16 19 L19.5 15.5",
            27 to
                ("M5.5 3.5 H8.5 A2 2 0 0 1 10.5 5.5 V8.5 A2 2 0 0 1 8.5 10.5 H5.5 A2 2 0 0 1 3.5 " +
                    "8.5 V5.5 A2 2 0 0 1 5.5 3.5 Z M15.5 3.5 H18.5 A2 2 0 0 1 20.5 5.5 V8.5 A2 2 0 0 " +
                    "1 18.5 10.5 H15.5 A2 2 0 0 1 13.5 8.5 V5.5 A2 2 0 0 1 15.5 3.5 Z M5.5 13.5 H8.5 " +
                    "A2 2 0 0 1 10.5 15.5 V18.5 A2 2 0 0 1 8.5 20.5 H5.5 A2 2 0 0 1 3.5 18.5 V15.5 A2 " +
                    "2 0 0 1 5.5 13.5 Z M15.5 13.5 H18.5 A2 2 0 0 1 20.5 15.5 V18.5 A2 2 0 0 1 18.5 " +
                    "20.5 H15.5 A2 2 0 0 1 13.5 18.5 V15.5 A2 2 0 0 1 15.5 13.5 Z"),
        )
    private val vectors =
        paths.mapValues { (id, data) ->
            ImageVector.Builder("PingClash-$id", 24.dp, 24.dp, 24f, 24f)
                .addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                .build()
        }

    operator fun get(index: Int) = vectors[if (index == 11) 1 else index] ?: vectors.getValue(24)
}

@Composable
internal fun AppGlyph(
    index: PcIcon,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalContentColor.current,
) {
    Icon(AppIcons[index.vectorId], contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
internal fun NavigationGlyph(index: PcIcon, modifier: Modifier, tint: Color) {
    AppGlyph(index, modifier, tint)
}
