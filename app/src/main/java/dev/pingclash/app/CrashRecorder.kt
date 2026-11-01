// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import android.content.Context
import android.os.Build
import dev.pingclash.mobile.Mobile
import java.io.File
import java.time.Instant

/** The last crash, as saved on this device; nothing is sent anywhere. */
internal data class CrashReport(val text: String, val savedAt: Long)

/** What the launch notice says: the app closed, or it contained an error and kept running. */
internal enum class CrashKind {
    CLOSED,
    CONTAINED,
}

/**
 * Keeps the cause of the last failure so it can be read after the next launch. Four sources:
 * - uncaught app exceptions, written as the process dies;
 * - fatal core errors (unrecovered Go panics end the process at once), written by Go's crash
 *   output;
 * - core panics contained during the run (one connection closed, the app kept running);
 * - Android's own exit record (Android 11+) for what neither can see: native crashes outside Go
 *   (WebView, graphics driver), "app not responding" kills with their thread dump, and low-memory
 *   or excessive-resource kills.
 *
 * At launch the previous run's sources become the "last report", and the files are re-armed.
 * Reports stay in no-backup app storage until replaced or deleted from Settings > About.
 */
internal object CrashRecorder {
    internal const val LIMIT = 96 * 1024
    private const val APP_LIVE = "app-live.txt"
    private const val CORE_LIVE = "core-live.txt"
    private const val CORE_RECOVERED = "core-recovered.txt"
    private const val HEADER_LIMIT = 512
    private const val FLAG_LIMIT = 32
    private const val LAST = "last.txt"
    private const val UNSEEN = "unseen"

    internal fun folder(context: Context) =
        File(context.noBackupFilesDir, "crash").apply { mkdirs() }

    /** Call first in Application.onCreate: collects the previous run, then catches this one. */
    fun install(app: Application, version: String) {
        runCatching { collect(app, version) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(folder(app), APP_LIVE).writeText(CrashText.throwable(error).take(LIMIT))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Call once the core has loaded (after [install]), so its errors also reach files. */
    fun armCore(app: Application): Boolean =
        runCatching {
                val dir = folder(app)
                Mobile.setCrashOutput(File(dir, CORE_LIVE).absolutePath) and
                    Mobile.setRecoveredPanicOutput(File(dir, CORE_RECOVERED).absolutePath)
            }
            .getOrDefault(false)

    private fun take(file: File): String {
        val text = if (file.isFile) CrashText.sanitize(readBounded(file, LIMIT)) else ""
        file.delete()
        return text
    }

    internal fun collect(context: Context, version: String) {
        val dir = folder(context)
        val fatal =
            listOfNotNull(
                take(File(dir, APP_LIVE)).ifBlank { null }?.let { "[App]\n$it" },
                take(File(dir, CORE_LIVE)).ifBlank { null }?.let { "[Core]\n$it" },
                SystemExits.since(context, dir)?.let { "[Android exit record]\n$it" },
            )
        val contained =
            take(File(dir, CORE_RECOVERED)).ifBlank { null }?.let { "[Core, contained]\n$it" }
        val parts = fatal + listOfNotNull(contained)
        if (parts.isEmpty()) return
        val header =
            "PingClash $version · ${Instant.now()} · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
        File(dir, LAST).writeText((header + "\n\n" + parts.joinToString("\n\n")).take(2 * LIMIT))
        File(dir, UNSEEN)
            .writeText(if (fatal.isNotEmpty()) CrashKind.CLOSED.name else CrashKind.CONTAINED.name)
    }

    fun last(context: Context): CrashReport? =
        File(folder(context), LAST)
            .takeIf { it.isFile }
            ?.let {
                val text = readBounded(it, 2 * LIMIT)
                // Re-sanitize legacy reports before display/clipboard export as well.
                val header =
                    text
                        .lineSequence()
                        .firstOrNull()
                        ?.takeIf { line -> line.startsWith("PingClash ") }
                        ?.take(HEADER_LIMIT)
                CrashReport(
                    listOfNotNull(header, CrashText.sanitize(text.substringAfter('\n', "")))
                        .joinToString("\n"),
                    it.lastModified(),
                )
            }

    private fun readBounded(file: File, limit: Int): String =
        file.bufferedReader().use { reader ->
            val chars = CharArray(limit)
            var used = 0
            while (used < limit) {
                val read = reader.read(chars, used, limit - used)
                if (read < 0) break
                used += read
            }
            String(chars, 0, used)
        }

    /** The kind of a new report, once, for the launch notice. */
    fun takeUnseen(context: Context): CrashKind? {
        val flag = File(folder(context), UNSEEN)
        if (!flag.isFile) return null
        val kind =
            runCatching { CrashKind.valueOf(readBounded(flag, FLAG_LIMIT).trim()) }
                .getOrDefault(CrashKind.CLOSED)
        return kind.takeIf { flag.delete() }
    }

    fun clear(context: Context) {
        File(folder(context), LAST).delete()
        File(folder(context), UNSEEN).delete()
    }
}
