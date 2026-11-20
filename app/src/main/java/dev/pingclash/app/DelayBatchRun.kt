// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.mobile.Mobile
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** How often a running batch is asked for its new results. */
private const val BATCH_POLL_MS = 100L

/** The core's codes for a measurement that ran out of time, not one that failed outright. */
private val TIMEOUT_CODES =
    setOf("timeout_stage_unknown", "proxy_channel_timeout", "proxy_quic_handshake_timeout")

internal fun delayState(code: String): String =
    when (code) {
        "proxy_delay_measured" -> "passed"
        "cancelled" -> "idle"
        in TIMEOUT_CODES -> "timeout"
        else -> "failed"
    }

/**
 * Measures members of one profile in the core, all in one batch: the profile is parsed once,
 * relayed nodes are measured alongside the others, and up to the chosen concurrency run at once.
 * Results reach [publish] as they arrive.
 */
internal class DelayBatchRun(
    private val profile: LocalProfile,
    private val target: String,
    private val settings: ClientSettings,
    private val active: () -> Boolean,
    private val runs: MutableSet<String>,
) {
    /**
     * Measures [names] once at [timeout]. Unless [final], a timeout is published as still queued
     * and returned for another pass; other results are final.
     */
    suspend fun measure(
        names: List<String>,
        timeout: Int,
        final: Boolean,
        publish: (String, ProxyDelay) -> Unit,
    ): List<String> {
        val id = start(names, timeout, publish) ?: return emptyList()
        val again = mutableListOf<String>()
        try {
            var done = false
            while (!done) {
                if (!active()) Mobile.cancelDelayBatch(id)
                delay(BATCH_POLL_MS)
                done = collect(id, final, again, publish)
            }
        } finally {
            runs.remove(id)
            Mobile.cancelDelayBatch(id)
        }
        return again
    }

    /** Publishes the batch's new results; true once it has finished. */
    private fun collect(
        id: String,
        final: Boolean,
        again: MutableList<String>,
        publish: (String, ProxyDelay) -> Unit,
    ): Boolean {
        val view = JSONObject(Mobile.delayBatchResults(id))
        val rows = view.optJSONArray("results") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val name = row.optString("name")
            val state = delayState(row.optString("code"))
            if (state == "timeout" && !final) {
                again += name
                publish(name, ProxyDelay("queued"))
            } else publish(name, ProxyDelay(state, row.optLong("elapsedMs")))
        }
        return view.optBoolean("done", true)
    }

    /** Starts the batch and returns its id, or null when nothing could start. */
    private fun start(
        names: List<String>,
        timeout: Int,
        publish: (String, ProxyDelay) -> Unit,
    ): String? {
        val byName = profile.nodes.associateBy { it.name }
        val known = names.filter { it in byName || it == DIRECT_OUTBOUND }
        names.filterNot { it in known }.forEach { publish(it, ProxyDelay("failed")) }
        val id = UUID.randomUUID().toString()
        runs.add(id)
        val started =
            known.isNotEmpty() &&
                JSONObject(Mobile.startDelayBatch(id, request(known, byName, timeout).toString()))
                    .optString("state") == "started"
        if (!started) {
            runs.remove(id)
            known.forEach { publish(it, ProxyDelay("busy")) }
        }
        return id.takeIf { started }
    }

    /** Members in the order to measure; a relayed node is built in the core from the profile. */
    private fun request(
        names: List<String>,
        byName: Map<String, LocalNode>,
        timeout: Int,
    ): JSONObject {
        val members = JSONArray()
        var relayed = false
        for (name in names) {
            // DIRECT has no configuration and needs no profile: the core builds it itself.
            val node = byName[name]
            relayed = relayed || node?.requiresProfile == true
            members.put(
                JSONObject()
                    .put("name", name)
                    .put("config", if (node == null || node.requiresProfile) "" else node.config)
            )
        }
        return JSONObject()
            .put("target", target)
            .put("timeoutMillis", timeout)
            .put("concurrency", settings.probeConcurrency)
            .put("unified", settings.unifiedDelay)
            .put("members", members)
            .apply { if (relayed) put("definition", profile.definition) }
    }
}
