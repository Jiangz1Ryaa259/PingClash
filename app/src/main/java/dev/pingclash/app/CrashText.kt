// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/** Crash messages and thread names are untrusted; export code locations, never their values. */
internal object CrashText {
    private val exception =
        Regex("^(?:(Caused by: |Suppressed: ))?([A-Za-z0-9_.$]+(?:Exception|Error))(?::.*)?$")
    private val javaFrame =
        Regex(
            "^at ([A-Za-z0-9_.$/<>-]+)\\(([A-Za-z0-9_.$-]+(?::[0-9]+)?|Native Method|Unknown Source)\\)$"
        )
    private val goFrame = Regex("^([A-Za-z0-9_./*()\\[\\]-]+)\\(.*\\)(?: \\+0x[0-9a-f]+)?$")
    private val goFile =
        Regex("^(?:.*[/\\\\])?([A-Za-z0-9_.-]+\\.go):([0-9]+)(?: \\+0x[0-9a-f]+)?$")
    private val buffer = Regex("buffer overflow: capacity [0-9]+,(?:start|end) [0-9]+, need [0-9]+")
    private val goroutine = Regex("^goroutine ([0-9]+) \\[.*]:$")
    private val count = Regex("^count ([0-9]+)(?:,.*)?$")
    private val exit =
        Regex(
            "^Android exit: (Native crash|App not responding|Killed for low memory|" +
                "Killed for excessive resource use|Failed to start) · [0-9TZ:.+-]+ · " +
                "importance [0-9]+ · PSS [0-9]+ KB · RSS [0-9]+ KB$"
        )
    private val section = setOf("[App]", "[Core]", "[Core, contained]", "[Android exit record]")

    fun sanitize(text: String): String =
        buildString {
                text.lineSequence().take(MAX_LINES).forEach { raw ->
                    safeLine(raw.trim().take(MAX_LINE))?.let { appendLine(it) }
                }
            }
            .ifBlank { if (text.isBlank()) "" else "[message omitted]\n" }

    private fun safeLine(line: String): String? =
        when {
            line in section -> line
            line.startsWith("Thread:") -> "Thread: app"
            line.startsWith("panic:") ->
                "panic: " +
                    (buffer.matchEntire(line.removePrefix("panic: "))?.value ?: "[message omitted]")
            line.startsWith("fatal error:") -> "fatal error: [message omitted]"
            line.startsWith("recovered panic in ") -> "recovered panic [message omitted]"
            line == "recovered panic [message omitted]" -> line
            else -> safeFrame(line)
        }

    private fun safeFrame(line: String): String? =
        exception.matchEntire(line)?.let { it.groupValues[1] + it.groupValues[2] }
            ?: exit.matchEntire(line)?.value
            ?: javaFrame.matchEntire(line)?.value
            ?: goFile.matchEntire(line)?.let { it.groupValues[1] + ":" + it.groupValues[2] }
            ?: goroutine.matchEntire(line)?.let {
                "goroutine ${it.groupValues[1]} [state omitted]:"
            }
            ?: count.matchEntire(line)?.let { "count ${it.groupValues[1]}" }
            ?: goFrame.matchEntire(line)?.let { it.groupValues[1] + "(...)" }

    fun throwable(error: Throwable): String =
        buildString {
                appendLine("Thread: app")
                generateSequence(error) { it.cause }
                    .take(MAX_CAUSES)
                    .forEachIndexed { index, cause ->
                        if (index > 0) append("Caused by: ")
                        appendLine(cause.javaClass.name)
                        cause.stackTrace.take(MAX_FRAMES).forEach { appendLine("at $it") }
                    }
            }
            .let(::sanitize)

    private const val MAX_LINES = 2000
    private const val MAX_LINE = 2048
    private const val MAX_CAUSES = 16
    private const val MAX_FRAMES = 64
}
