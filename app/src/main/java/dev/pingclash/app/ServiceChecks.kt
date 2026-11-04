// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Context
import dev.pingclash.diagnostics.RunControl
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class ServiceTarget(val id: String, val name: String, val url: String)

// Fixed public HTTPS targets. Brand ordering follows the services users commonly check.
// Reachable means this network gets an HTTP answer from the site; it does not claim account
// login or service unlock.
internal val serviceTargets =
    listOf(
        ServiceTarget("google", "Google", "https://www.google.com/generate_204"),
        ServiceTarget("chatgpt", "ChatGPT", "https://chatgpt.com/"),
        ServiceTarget("gemini", "Gemini", "https://gemini.google.com/"),
        ServiceTarget("youtube", "YouTube", "https://www.youtube.com/generate_204"),
        ServiceTarget("github", "GitHub", "https://github.com/"),
        ServiceTarget("claude", "Claude", "https://claude.ai/"),
        ServiceTarget("cloudflare", "Cloudflare", "https://www.cloudflare.com/cdn-cgi/trace"),
        ServiceTarget("netflix", "Netflix", "https://www.netflix.com/"),
        ServiceTarget("spotify", "Spotify", "https://open.spotify.com/"),
        ServiceTarget("discord", "Discord", "https://discord.com/"),
        ServiceTarget("telegram", "Telegram", "https://web.telegram.org/"),
        ServiceTarget("bilibili", "Bilibili", "https://www.bilibili.com/"),
    )

internal enum class WebsiteFailure {
    TIMEOUT,
    RESET,
    DNS,
    CERTIFICATE,
    REFUSED,
    NETWORK,
    CANCELLED,
    RESTRICTED,
    OTHER,
}

internal sealed interface Latency {
    data object Running : Latency

    data class Measured(val elapsed: Long) : Latency

    data class Failed(val reason: WebsiteFailure, val httpStatus: Int = 0, val elapsed: Long = 0) :
        Latency
}

internal data class ServiceCheckState(
    val running: Boolean = false,
    val values: Map<String, Latency> = emptyMap(),
)

internal fun serviceSelection(ids: List<String>) = serviceTargets.filter { it.id in ids.toSet() }

/** User-triggered HTTPS over this app's current system route, independent of node URLTest. */
internal class ServiceChecks(
    private val scope: CoroutineScope,
    context: Context,
    // Tests replace the route source to change it mid-probe; the app uses the monitor.
    private val routeOverride: (() -> WebsiteRoute?)? = null,
    private val routeChanged: () -> Unit = {},
) : Closeable {
    private val mutable = MutableStateFlow(ServiceCheckState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val gate = Semaphore(4)
    private val runs = ConcurrentHashMap.newKeySet<RunControl>()
    private var generation = 0
    private val network =
        WebsiteNetworkMonitor(context) {
            cancel()
            routeChanged()
        }

    fun start(timeout: Int, ids: List<String>, targets: List<ServiceTarget> = serviceTargets) {
        synchronized(lock) {
            val selected =
                targets
                    .filter { it.id in ids.toSet() && it.id !in jobs }
                    .distinctBy { it.id }
                    .take(12)
            if (selected.isEmpty()) return
            val ticket = generation
            val route = route()
            if (route == null) {
                mutable.update {
                    it.copy(
                        values =
                            it.values +
                                selected.associate { target ->
                                    target.id to Latency.Failed(WebsiteFailure.NETWORK)
                                }
                    )
                }
                return
            }
            val limit = timeout.coerceIn(1000, 10000)
            mutable.update {
                it.copy(
                    running = true,
                    values =
                        it.values + selected.associate { target -> target.id to Latency.Running },
                )
            }
            selected.forEach { target ->
                jobs[target.id] =
                    scope.launch(Dispatchers.IO) { probe(target, limit, ticket, route) }
            }
        }
    }

    private suspend fun probe(target: ServiceTarget, limit: Int, ticket: Int, route: WebsiteRoute) {
        try {
            gate.withPermit {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val control = RunControl(limit.toLong())
                runs.add(control)
                try {
                    if (!current(ticket, route)) return@withPermit
                    val result = WebsiteProbe.run(target, limit, control, route.network)
                    if (current(ticket, route)) publish(target.id, result, ticket)
                } finally {
                    runs.remove(control)
                    control.close()
                }
            }
        } finally {
            val completedJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(lock) {
                if (ticket == generation && jobs[target.id] === completedJob) {
                    jobs.remove(target.id)
                    // A probe that ended without a result (its route changed before the
                    // network callback arrived, which then never resets this batch) must
                    // not leave its tile on "Testing" forever: return it to idle.
                    mutable.update {
                        it.copy(
                            running = jobs.isNotEmpty(),
                            values =
                                if (it.values[target.id] == Latency.Running) it.values - target.id
                                else it.values,
                        )
                    }
                }
            }
        }
    }

    private fun route(): WebsiteRoute? = routeOverride?.invoke() ?: network.current()

    private fun current(ticket: Int, route: WebsiteRoute): Boolean =
        synchronized(lock) { ticket == generation && route() == route }

    private fun publish(id: String, value: Latency, ticket: Int) {
        synchronized(lock) {
            if (ticket != generation) return
            jobs.remove(id)
            mutable.update {
                it.copy(running = jobs.isNotEmpty(), values = it.values + (id to value))
            }
        }
        scope.launch {
            delay(60000)
            synchronized(lock) {
                if (ticket == generation)
                    mutable.update {
                        if (it.values[id] === value) it.copy(values = it.values - id) else it
                    }
            }
        }
    }

    fun cancel() {
        val controls =
            synchronized(lock) {
                generation++
                jobs.values.forEach { it.cancel() }
                jobs.clear()
                mutable.value = ServiceCheckState()
                runs.toList()
            }
        // A disconnect may wait for a blocked reader. Never close probe sockets on the UI thread.
        scope.launch(Dispatchers.IO) { controls.forEach { it.cancel() } }
    }

    override fun close() {
        network.close()
        cancel()
    }
}
