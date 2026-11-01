// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.time.Instant

/**
 * Android's record of why earlier processes of this app ended (Android 11+). It covers what the app
 * and core cannot write themselves: native crashes outside Go, "app not responding" kills (with
 * Android's thread dump), and low-memory or excessive-resource kills, which also take the VPN down.
 * Java crashes are left out: the app's own handler already saved their full trace.
 */
internal object SystemExits {
    private const val SEEN = "exit-seen"
    private const val TRACE_LIMIT = 48 * 1024
    private const val HISTORY = 8

    private val notable =
        mapOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE to "Native crash",
            ApplicationExitInfo.REASON_ANR to "App not responding",
            ApplicationExitInfo.REASON_LOW_MEMORY to "Killed for low memory",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to
                "Killed for excessive resource use",
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "Failed to start",
        )

    /**
     * Notable exits since the last launch, or null. The first run only records a baseline, so
     * history from before the recorder existed is never reported as new.
     */
    fun since(context: Context, dir: File): String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else read(context, dir)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun read(context: Context, dir: File): String? {
        val seenFile = File(dir, SEEN)
        val seen = seenFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val exits =
            runCatching {
                    context
                        .getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(context.packageName, 0, HISTORY)
                }
                .getOrDefault(emptyList())
        val newest = exits.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        seenFile.writeText(newest.toString())
        // The first run only records a baseline: older history is not new to this report.
        return exits
            .filter { seen != null && it.timestamp > seen && it.reason in notable }
            .sortedBy { it.timestamp }
            .joinToString("\n\n") { describe(it) }
            .ifBlank { null }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(exit: ApplicationExitInfo): String {
        val head =
            "Android exit: ${notable[exit.reason]} · ${Instant.ofEpochMilli(exit.timestamp)} · " +
                "importance ${exit.importance} · " +
                "PSS ${exit.pss} KB · RSS ${exit.rss} KB"
        // ANR traces are text; native-crash traces are binary tombstones, so they are skipped.
        val trace =
            if (exit.reason == ApplicationExitInfo.REASON_ANR)
                runCatching {
                        exit.traceInputStream?.bufferedReader()?.use { reader ->
                            val buffer = CharArray(TRACE_LIMIT)
                            val read = reader.read(buffer)
                            if (read > 0) CrashText.sanitize(String(buffer, 0, read)) else null
                        }
                    }
                    .getOrNull()
            else null
        return if (trace == null) head else "$head\n\n$trace"
    }
}
