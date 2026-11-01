// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.mobile.Mobile
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

// URLs, authorization headers and payloads are private profile data. Persist
// only through ProfileRepository's authenticated encryption, never as filenames.
data class RemoteProfile(
    val original: String,
    val bodies: Map<String, String>,
    val updated: Map<String, Long>,
    val retryAfter: Long = 0,
    val error: String = "",
    val pending: Boolean = false,
) {
    fun json() =
        JSONObject()
            .put("original", original)
            .put("bodies", JSONObject(bodies))
            .put("updated", JSONObject(updated))
            .put("retryAfter", retryAfter)
            .put("error", error)
            .put("pending", pending)

    companion object {
        fun read(value: JSONObject?): RemoteProfile? =
            value?.let {
                val original = it.getString("original")
                require(original.toByteArray().size <= 2 * 1024 * 1024)
                val bodies = it.getJSONObject("bodies")
                val times = it.getJSONObject("updated")
                require(
                    bodies.length() <= MAX_REMOTE_PROVIDERS &&
                        times.length() <= MAX_REMOTE_PROVIDERS
                )
                RemoteProfile(
                    original,
                    bodies.keys().asSequence().associateWith(bodies::getString),
                    times.keys().asSequence().associateWith(times::getLong),
                    it.optLong("retryAfter"),
                    providerErrorRecord(it.optString("error")),
                    it.optBoolean("pending"),
                )
            }
    }
}

internal fun providerFailure(reply: JSONObject) =
    ProviderFailure(
        reply.optInt("index"),
        reply.optString("kind").takeIf { it in setOf("proxy", "rule") }.orEmpty(),
        reply.optString("code"),
    )

internal data class PreparedRemoteProfile(val content: String, val remote: RemoteProfile?)

internal object RemoteProfiles {
    fun nextDelaySeconds(remote: RemoteProfile): Long {
        val now = Instant.now().epochSecond
        if (remote.retryAfter > now || remote.pending)
            return (remote.retryAfter - now).coerceAtLeast(0)
        val specs = plan(remote.original)
        return (0 until specs.length())
            .mapNotNull { i ->
                val spec = specs.getJSONObject(i)
                val interval = spec.getInt("interval")
                if (spec.optString("mode") == "inline") null
                else if (remote.bodies[spec.getString("id")] == DEFERRED_PROVIDER_BODY) 0L
                else if (interval == 0) null
                else ((remote.updated[spec.getString("id")] ?: 0) + interval - now).coerceAtLeast(0)
            }
            .minOrNull() ?: Long.MAX_VALUE
    }

    fun plan(text: String): JSONArray {
        val reply = JSONObject(Mobile.planRemoteProviders(text))
        if (reply.optString("state") != "passed") throw providerFailure(reply)
        return reply.optJSONArray("providers") ?: JSONArray()
    }

    fun resolve(text: String, bodies: Map<String, String>): String {
        val reply = JSONObject(Mobile.resolveRemoteProviders(text, JSONObject(bodies).toString()))
        if (reply.optString("state") != "passed") throw providerFailure(reply)
        return reply.getString("content")
    }

    fun load(
        text: String,
        userAgent: String,
        old: RemoteProfile? = null,
        force: Boolean = false,
        now: Long = Instant.now().epochSecond,
    ) = loadWithFiles(text, userAgent, ProviderLoadOptions(old, force, now))

    /**
     * A new import: a rule provider that cannot be downloaded yet (typically blocked before the VPN
     * is up) is saved as an empty deferred set and fetched by a later refresh instead of rejecting
     * the whole profile.
     */
    fun loadForImport(text: String, userAgent: String, deferRules: Boolean = true) =
        loadWithFiles(text, userAgent, ProviderLoadOptions(force = true, deferRules = deferRules))

    fun loadDocument(document: ProfileDocument, userAgent: String) =
        loadWithFiles(
            document.content,
            userAgent,
            ProviderLoadOptions(force = true, localBodies = document.localBodies, deferRules = true),
        )

    private fun loadWithFiles(
        text: String,
        userAgent: String,
        options: ProviderLoadOptions,
    ): PreparedRemoteProfile {
        val old = options.old
        val force = options.force
        val now = options.now
        val localBodies = options.localBodies
        val specs = plan(text)
        if (specs.length() == 0) return PreparedRemoteProfile(text, null)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        val bodies = mutableMapOf<String, String>()
        val updated = mutableMapOf<String, Long>()
        val pending = mutableListOf<JSONObject>()
        val previous = old?.takeIf { it.original == text }
        for (i in 0 until specs.length()) {
            val spec = specs.getJSONObject(i)
            val id = spec.getString("id")
            if (spec.optString("mode") == "file") {
                bodies[id] =
                    localBodies[id]
                        ?: previous?.bodies?.get(id)
                        ?: throw providerLocalFileFailure(spec)
                updated[id] = previous?.updated?.get(id) ?: now
            } else if (spec.optString("mode") != "inline") {
                val stamp = previous?.updated?.get(id) ?: 0
                if (cachedProviderIsFresh(previous, spec, force, now, stamp)) {
                    bodies[id] = requireNotNull(previous).bodies.getValue(id)
                    updated[id] = stamp
                } else pending += spec
            }
        }
        ProviderDownloads(
                ProviderFetchRequest(text, userAgent, deadline),
                bodies to updated,
                now,
            ) { spec ->
                options.deferRules ||
                    previous?.bodies?.get(spec.getString("id")) == DEFERRED_PROVIDER_BODY
            }
            .run(pending)
        return PreparedRemoteProfile(resolve(text, bodies), RemoteProfile(text, bodies, updated))
    }

    fun due(remote: RemoteProfile, now: Long = Instant.now().epochSecond): Boolean {
        if (now < remote.retryAfter) return false
        val specs = plan(remote.original)
        return (0 until specs.length()).any { i ->
            val spec = specs.getJSONObject(i)
            val last = remote.updated[spec.getString("id")] ?: 0
            spec.optString("mode") != "inline" &&
                (remote.bodies[spec.getString("id")] == DEFERRED_PROVIDER_BODY ||
                    spec.getInt("interval") > 0 &&
                        (now < last || now - last >= spec.getInt("interval")))
        }
    }
}

private const val MAX_REMOTE_PROVIDERS = 128

/** Stored body of a rule provider deferred at import; the core resolves it to an empty set. */
internal const val DEFERRED_PROVIDER_BODY = ""

private const val PARALLEL_PROVIDER_DOWNLOADS = 4

private data class ProviderLoadOptions(
    val old: RemoteProfile? = null,
    val force: Boolean = false,
    val now: Long = Instant.now().epochSecond,
    val localBodies: Map<String, String> = emptyMap(),
    val deferRules: Boolean = false,
)

private fun cachedProviderIsFresh(
    previous: RemoteProfile?,
    spec: JSONObject,
    force: Boolean,
    now: Long,
    stamp: Long,
): Boolean {
    if (force || previous?.bodies?.get(spec.getString("id")).isNullOrEmpty()) return false
    val interval = spec.getInt("interval")
    return interval == 0 || now >= stamp && now - stamp < interval
}

private data class ProviderFetchRequest(val text: String, val userAgent: String, val deadline: Long)

private class ProviderDownloads(
    private val request: ProviderFetchRequest,
    cache: Pair<MutableMap<String, String>, MutableMap<String, Long>>,
    private val now: Long,
    private val deferrable: (JSONObject) -> Boolean,
) {
    private val bodies = cache.first
    private val updated = cache.second
    private val budget = ProviderCacheBudget()

    fun run(pending: MutableList<JSONObject>) {
        bodies.values.forEach { budget.include(it, JSONObject().put("index", 0).put("kind", "")) }
        pending.sortBy { if (it.getString("kind") == "rule") 2 else if (direct(it)) 0 else 1 }
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (System.nanoTime() >= request.deadline) {
                pending.removeAll(::defer)
                if (pending.isNotEmpty()) checkSubscriptionDeadline(request.deadline)
            } else {
                // Evaluate both: proxied downloads can use proxy bodies fetched directly.
                val fetched = downloadDirect(pending)
                if (!downloadProxied(pending) && !fetched) stalled(pending)
            }
        }
    }

    private fun direct(spec: JSONObject) = spec.optString("downloadProxy") in listOf("", "DIRECT")

    // Direct downloads are independent; fetch a few at a time instead of one by one.
    private fun downloadDirect(pending: MutableList<JSONObject>): Boolean {
        val specs = pending.filter(::direct)
        downloadInParallel(specs) { downloadProvider(it, request.userAgent, request.deadline) }
            .forEachIndexed { i, result -> settle(specs[i], result) }
        pending.removeAll(specs)
        return specs.isNotEmpty()
    }

    private fun downloadProxied(pending: MutableList<JSONObject>): Boolean {
        var progress = false
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            checkSubscriptionDeadline(request.deadline, Thread.currentThread().isInterrupted)
            val spec = iterator.next()
            val result = runCatching {
                downloadThroughDeclaredProxy(
                    request.text,
                    bodies,
                    spec,
                    request.userAgent,
                    request.deadline,
                )
            }
            // null: the declared proxy is not available yet; retry after other progress.
            if (result.isFailure || result.getOrNull() != null) {
                settle(spec, result.map { requireNotNull(it) })
                iterator.remove()
                progress = true
            }
        }
        return progress
    }

    private fun settle(spec: JSONObject, result: Result<String>) {
        result.fold(
            { content ->
                bodies[spec.getString("id")] = content
                updated[spec.getString("id")] = now
                budget.include(content, spec)
            },
            { failure -> if (failure is InterruptedException || !defer(spec)) throw failure },
        )
    }

    // Only rule providers may wait; a proxy provider is needed to build groups.
    private fun defer(spec: JSONObject): Boolean {
        if (spec.getString("kind") != "rule" || !deferrable(spec)) return false
        bodies[spec.getString("id")] = DEFERRED_PROVIDER_BODY
        updated[spec.getString("id")] = 0
        return true
    }

    private fun stalled(pending: MutableList<JSONObject>) {
        pending.removeAll(::defer)
        val spec = pending.firstOrNull() ?: return
        throw ProviderFailure(
            spec.getInt("index"),
            spec.getString("kind"),
            "provider_proxy_unavailable",
        )
    }
}

private fun <T> downloadInParallel(
    specs: List<JSONObject>,
    download: (JSONObject) -> T,
): List<Result<T>> {
    if (specs.isEmpty()) return emptyList()
    val pool =
        java.util.concurrent.Executors.newFixedThreadPool(
            minOf(PARALLEL_PROVIDER_DOWNLOADS, specs.size)
        )
    try {
        val futures = specs.map { spec -> pool.submit<T> { download(spec) } }
        return futures.map { future ->
            try {
                Result.success(future.get())
            } catch (failure: java.util.concurrent.ExecutionException) {
                Result.failure(failure.cause ?: failure)
            }
        }
    } finally {
        // Also interrupts the remaining downloads when the caller is cancelled.
        pool.shutdownNow()
    }
}

private fun downloadThroughDeclaredProxy(
    text: String,
    bodies: Map<String, String>,
    spec: JSONObject,
    userAgent: String,
    deadline: Long,
): String? {
    if (spec.optString("downloadProxy") in listOf("", "DIRECT"))
        return downloadProvider(spec, userAgent, deadline)
    val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
    if (remaining <= 0) throw java.net.SocketTimeoutException("provider_deadline")
    val reply =
        JSONObject(
            Mobile.fetchRemoteProvider(
                text,
                JSONObject(bodies).toString(),
                spec.getString("id"),
                userAgent,
                remaining.coerceAtMost(PROVIDER_FETCH_MILLIS).toInt().toLong(),
            )
        )
    return when {
        reply.optString("state") == "passed" -> ProviderBodyCache.pack(reply.getString("content"))
        reply.optString("code") == "provider_proxy_unavailable" -> null
        else -> throw providerFailure(reply)
    }
}

private const val PROVIDER_FETCH_MILLIS = 30_000L

private class ProviderCacheBudget {
    private var decoded = 0L
    private var encoded = 0L

    fun include(content: String, spec: JSONObject) {
        decoded += ProviderBodyCache.decodedSize(content)
        encoded += content.toByteArray().size
        val code =
            when {
                decoded > ProviderBodyCache.TOTAL_BYTES -> "provider_total_limit"
                encoded > ProviderBodyCache.CACHE_BYTES -> "provider_cache_limit"
                else -> return
            }
        throw ProviderFailure(spec.getInt("index"), spec.getString("kind"), code)
    }
}
