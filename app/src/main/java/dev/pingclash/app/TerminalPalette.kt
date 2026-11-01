// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.core.graphics.toColorInt
import com.termux.terminal.TerminalColors
import java.util.Properties

/** Soft ANSI colors; terminal contents and exit/error text are never rewritten. */
internal object TerminalPalette {
    val background = "#10151f".toColorInt()
    private const val PROMPT_BLUE = 75
    private const val PROMPT_GREEN = 114
    private const val PROMPT_YELLOW = 222
    private const val PROMPT_PURPLE = 183
    private val ansi =
        listOf(
            "#18212f",
            "#f28b82",
            "#81c995",
            "#fdd663",
            "#8ab4f8",
            "#c58af9",
            "#78d9ec",
            "#e2e8f0",
            "#718096",
            "#ffaaa3",
            "#a8dab5",
            "#ffe29a",
            "#aecbfa",
            "#dbb5ff",
            "#a1e9f5",
            "#ffffff",
        )

    fun install() {
        val properties =
            Properties().apply {
                setProperty("background", "#10151f")
                setProperty("foreground", "#e2e8f0")
                setProperty("cursor", "#8ab4f8")
                ansi.forEachIndexed { index, color -> setProperty("color$index", color) }
            }
        TerminalColors.COLOR_SCHEME.updateWith(properties)
    }

    // mksh's documented Ctrl-A/CR prefix and nonprinting delimiters keep ANSI
    // prompt bytes out of the command editor's column/wrapping calculations.
    private fun color(code: Int) = "\u0001\u001b[38;5;${code}m\u0001"

    val prompt =
        "\u0001\r" +
            color(PROMPT_BLUE) +
            "Ping" +
            color(PROMPT_GREEN) +
            "Clash" +
            color(PROMPT_YELLOW) +
            " · " +
            color(PROMPT_PURPLE) +
            "\$ " +
            "\u0001\u001b[0m\u0001"
}
