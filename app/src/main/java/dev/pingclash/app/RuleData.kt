// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.util.AtomicFile
import dev.pingclash.mobile.Mobile
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Fixed bundled data only; never fetches a URL or trusts a subscription path. */
internal object RuleData {
    private var pending: CompletableFuture<Boolean>? = null

    @Synchronized
    fun prepare(context: Context): CompletableFuture<Boolean> {
        pending?.let {
            return it
        }
        val result = CompletableFuture<Boolean>()
        pending = result
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "rule-data-init") }
        executor.execute {
            try {
                result.complete(install(context.applicationContext))
            } finally {
                executor.shutdown()
            }
        }
        return result
    }

    private val hashes =
        mapOf(
            "GeoIP.dat" to "a6271d3c96361d96156e6d3f7cf1550ef21b00d2f951deb772f464db91e89ac5",
            "GeoSite.dat" to "c57333596b44aa061e84c3c224e97a4475c759a5e3baab1fcda002e48b4dc313",
            "ASN.mmdb" to "4384bfcfb594391bc4e0ab37751f44661eaf7cadbeee28a90a6b82dc935fec4c",
        )

    fun install(context: Context): Boolean =
        runCatching {
                val directory = File(context.filesDir, "ruledata").apply { mkdirs() }
                hashes.forEach { (name, _) ->
                    val file = File(directory, name)
                    if (!file.isFile) copy(context, directory, name)
                }
                // The native boundary performs the sole full integrity pass. Repair a
                // failed/partial private installation and verify again, off the UI thread.
                if (Mobile.initializeRuleData(directory.absolutePath)) true
                else {
                    hashes.keys.forEach { copy(context, directory, it) }
                    Mobile.initializeRuleData(directory.absolutePath)
                }
            }
            .getOrDefault(false)

    private fun copy(context: Context, directory: File, name: String) {
        val atomic = AtomicFile(File(directory, name))
        val stream = atomic.startWrite()
        var complete = false
        try {
            context.assets.open("ruledata/$name").use { it.copyTo(stream) }
            complete = true
        } finally {
            if (complete) atomic.finishWrite(stream) else atomic.failWrite(stream)
        }
    }
}
