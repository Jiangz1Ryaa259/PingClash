// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A short user-requested save belongs to the app, not a rotating composition. */
internal object ReportExporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(context: Context, uri: Uri, markdown: String) {
        val app = context.applicationContext
        scope.launch {
            val saved =
                runCatching {
                        check(markdown.startsWith("# PingClash"))
                        requireNotNull(app.contentResolver.openOutputStream(uri, "wt"))
                            .bufferedWriter(Charsets.UTF_8)
                            .use { it.write(markdown) }
                    }
                    .isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(
                        app,
                        if (saved) tr(R.string.report_saved)
                        else tr(R.string.could_not_save_the_report_try_again),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }
}
