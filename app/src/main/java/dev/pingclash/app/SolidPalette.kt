// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

internal const val MAX_SOLID_SWATCHES = 48
internal const val SOLID_PALETTE_VERSION = 3
internal const val RETIRED_DOVE_COLOR = "#E8E3DF"

internal class Swatch(val hex: String, val name: Int)

/**
 * What the colour editor is doing: adding a colour, or changing [original]. It opens on [start].
 */
internal class ColorEdit(
    val original: String?,
    val start: String,
    val palette: List<String> = emptyList(),
    val warnDark: Boolean = true,
)

// Four luxury-inspired presets softened for background use; custom colours remain editable.
internal val PALETTE =
    listOf(
        Swatch("#B1F0E9", R.string.swatch_robins_egg), // the jeweller's robin's-egg blue
        Swatch("#FCD5BB", R.string.swatch_apricot), // the saddler's orange
        Swatch("#F5E1BF", R.string.swatch_champagne), // jeweller's and champagne-house gold
        Swatch("#FCD2DC", R.string.swatch_blush), // the couture house pink
    )

internal val DEFAULT_SOLID_SWATCHES = PALETTE.map { it.hex }

// Historical built-in lists used only to migrate saved palettes.
private val UNTOUCHED_EARLIER_PRESETS =
    setOf(
        listOf("#F0EEE9", "#72EDE5", "#D0E2F7", "#99BEFF", "#FCD2DC", "#FFE561"),
        listOf(
            "#F0EEE9",
            "#FAF5E8",
            "#F2E3D0",
            "#F5E1BF",
            "#E8DED3",
            "#E9EBF0",
            "#B1F0E9",
            "#FCD5BB",
            "#FCD2DC",
            "#CEF0BB",
            "#E8E3DF",
            "#D0E2F7",
            "#E8CBBA",
            "#FFD2B8",
            "#D2D2F7",
            "#FCD8D7",
            "#D0DEF7",
            "#DED4FA",
            "#D6EBCC",
            "#FCEEC0",
        ),
        listOf(
            "#72EDE5",
            "#99BEFF",
            "#FFA8E5",
            "#FF9E8F",
            "#7EE6A1",
            "#FFE561",
            "#F0EEE9",
            "#FAF5E8",
            "#F2E3D0",
            "#F5E1BF",
            "#E8DED3",
            "#E9EBF0",
            "#B1F0E9",
            "#FCD5BB",
            "#FCD2DC",
            "#CEF0BB",
            "#E8E3DF",
            "#D0E2F7",
            "#E8CBBA",
            "#FFD2B8",
            "#D2D2F7",
            "#FCD8D7",
            "#D0DEF7",
            "#DED4FA",
            "#D6EBCC",
            "#FCEEC0",
        ),
    )

internal fun swatchName(hex: String) = PALETTE.firstOrNull { it.hex == hex }?.name

/** Valid, distinct, bounded; an empty or unreadable list falls back to the presets. */
internal fun normalizeSwatches(values: List<String>): List<String> =
    values
        .map { it.uppercase() }
        .filter(::validHexColor)
        .distinct()
        .take(MAX_SOLID_SWATCHES)
        .takeUnless { it.isEmpty() } ?: DEFAULT_SOLID_SWATCHES

/** Drop old bundled colours even when that old palette also contains custom additions. */
internal fun migrateSolidSwatches(values: List<String>, version: Int): List<String> {
    val swatches = normalizeSwatches(values)
    return when {
        version >= SOLID_PALETTE_VERSION -> swatches
        version >= 2 -> normalizeSwatches(swatches.filterNot { it == RETIRED_DOVE_COLOR })
        else -> {
            val oldBuiltIns = UNTOUCHED_EARLIER_PRESETS.flatten().toSet()
            normalizeSwatches(DEFAULT_SOLID_SWATCHES + swatches.filterNot { it in oldBuiltIns })
        }
    }
}
