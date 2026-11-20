// SPDX-License-Identifier: GPL-3.0-only
// HTTP statuses and ranges read as themselves.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.pingclash.diagnostics.RunControl
import java.io.Closeable
import java.io.InputStream
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a service's own pages say about access from where the route comes out. */
internal enum class UnlockStatus {
    RUNNING,
    AVAILABLE,
    /** Netflix: only its own productions, not the licensed catalogue. */
    ORIGINALS_ONLY,
    UNAVAILABLE,
    /** No answer that says either way (timeout, reset, an unexpected page). */
    UNKNOWN,
    /** The check runs through the VPN's route, so the VPN must be connected. */
    NEEDS_VPN,
}

/** An indication, not a guarantee: [region] is the country the service sees, when it says. */
internal data class UnlockResult(
    val status: UnlockStatus,
    val region: String = "",
    /** [websiteRequestKey] when it was checked; a result for other routing is not shown. */
    val key: Int = 0,
)

/**
 * Services whose access can be checked, and how. These pages and markers change: when a check
 * starts reporting Unknown for everyone, update its markers here (see `todo.md`).
 */
internal val UNLOCK_SERVICES = listOf("netflix", "youtube", "chatgpt", "claude")

/** Two licensed (not Netflix-produced) titles; a full catalogue has at least one of them. */
internal val NETFLIX_TITLES = listOf("81280792", "70143836")

private val TRACE_REGION = Regex("^loc=([A-Z]{2})$", RegexOption.MULTILINE)
private val NETFLIX_REGION = Regex("netflix\\.com/([a-z]{2})(?:-[a-z]{2})?/title/")
private val YOUTUBE_REGION = Regex("\"(?:countryCode|GL)\":\"([A-Z]{2})\"")
private const val YOUTUBE_REFUSAL = "Premium is not available in your country"

/** The country in a Cloudflare trace ("loc=JP"), or empty. */
internal fun traceRegion(body: String): String =
    TRACE_REGION.find(body)?.groupValues?.get(1).orEmpty()

/** The country of a localized Netflix title address (/jp/title/, /de-en/title/), or empty. */
internal fun netflixRegion(location: String?): String =
    location?.let { NETFLIX_REGION.find(it)?.groupValues?.get(1)?.uppercase(Locale.ROOT) }.orEmpty()

/** Netflix from the title pages' statuses: 200 shows the title, 404 hides it, 403 refuses. */
internal fun netflixVerdict(statuses: List<Int>, region: String): UnlockResult =
    UnlockResult(
        when {
            statuses.any { it == HTTP_OK } -> UnlockStatus.AVAILABLE
            statuses.isNotEmpty() && statuses.all { it == HTTP_NOT_FOUND } ->
                UnlockStatus.ORIGINALS_ONLY
            statuses.any { it == HTTP_FORBIDDEN } -> UnlockStatus.UNAVAILABLE
            else -> UnlockStatus.UNKNOWN
        },
        region,
    )

/** YouTube Premium from its page: the refusal text, or the page itself with its country. */
internal fun youtubeVerdict(reply: WebsiteReply): UnlockResult {
    val region = YOUTUBE_REGION.find(reply.preview)?.groupValues?.get(1).orEmpty()
    return UnlockResult(
        when {
            reply.preview.contains(YOUTUBE_REFUSAL, ignoreCase = true) -> UnlockStatus.UNAVAILABLE
            reply.status == HTTP_OK -> UnlockStatus.AVAILABLE
            else -> UnlockStatus.UNKNOWN
        },
        region,
    )
}

/** ChatGPT and Claude: the same refusal markers as the website checks, any other answer is open. */
internal fun regionPageVerdict(id: String, reply: WebsiteReply, region: String): UnlockResult {
    val target = serviceTargets.first { it.id == id }
    return UnlockResult(
        when {
            reply.status <= 0 -> UnlockStatus.UNKNOWN
            websiteResponseFailure(target, reply) == WebsiteFailure.RESTRICTED ->
                UnlockStatus.UNAVAILABLE
            else -> UnlockStatus.AVAILABLE
        },
        region,
    )
}

private const val HTTP_OK = 200
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val UNLOCK_TIMEOUT_MS = 10_000
/** YouTube names the country well into its page; never read more than this. */
private const val UNLOCK_READ_LIMIT = 512 * 1024

/**
 * Checks a service's access over the app's default route (the VPN when it is connected, so the
 * service routing choice applies), in memory only. User-started, one check per service at a time.
 */
internal class UnlockChecks(private val scope: CoroutineScope, private val context: Context) {
    private val mutable = MutableStateFlow<Map<String, UnlockResult>>(emptyMap())
    val state = mutable.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /**
     * Checks [id] for the route [key]. A check already running for the same route is left alone;
     * one for an older route (the route changed meanwhile) is dropped and this one runs instead.
     */
    fun check(id: String, key: Int) {
        val now = mutable.value[id]
        if (id !in UNLOCK_SERVICES || now?.status == UnlockStatus.RUNNING && now.key == key) return
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        val network = vpnNetwork()
        if (network == null) {
            mutable.update { it + (id to UnlockResult(UnlockStatus.NEEDS_VPN, key = key)) }
            return
        }
        mutable.update { it + (id to UnlockResult(UnlockStatus.RUNNING, key = key)) }
        val job =
            scope.launch(Dispatchers.IO) {
                val result =
                    RunControl(UNLOCK_TIMEOUT_MS * 3L).use { control ->
                        runCatching { probe(id, network, control) }
                            .getOrDefault(UnlockResult(UnlockStatus.UNKNOWN))
                    }
                // Only the check still wanted reports: an older route's answer is dropped.
                mutable.update {
                    val current = it[id]
                    if (current?.status == UnlockStatus.RUNNING && current.key == key)
                        it + (id to result.copy(key = key))
                    else it
                }
            }
        synchronized(jobs) { jobs[id] = job }
    }

    /**
     * Stops every check running now (the VPN session they went through has ended); their late
     * answers are dropped, and the services show no result until checked again.
     */
    fun cancelAll() {
        synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
        mutable.update { results -> results.filterValues { it.status != UnlockStatus.RUNNING } }
    }

    private fun vpnNetwork(): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return null
        val vpn =
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return network.takeIf {
            vpn == true && VpnRuntime.state.value.stage == VpnPhase.ESTABLISHED
        }
    }

    private fun probe(id: String, network: Network, control: RunControl): UnlockResult =
        when (id) {
            "netflix" -> {
                val replies =
                    NETFLIX_TITLES.map { title ->
                        fetchFollowingOnce(network, "https://www.netflix.com/title/$title", control)
                    }
                netflixVerdict(
                    replies.map { it.second.status },
                    replies
                        .firstNotNullOfOrNull { netflixRegion(it.first).ifEmpty { null } }
                        .orEmpty(),
                )
            }
            "youtube" -> youtubeVerdict(fetch(network, "https://www.youtube.com/premium", control))
            else -> {
                val host = if (id == "chatgpt") "chatgpt.com" else "claude.ai"
                val trace = fetch(network, "https://$host/cdn-cgi/trace", control)
                regionPageVerdict(
                    id,
                    fetch(network, "https://$host/", control),
                    traceRegion(trace.preview),
                )
            }
        }

    /** A Netflix title answers with a redirect to its localized address; follow that once. */
    private fun fetchFollowingOnce(
        network: Network,
        url: String,
        control: RunControl,
    ): Pair<String?, WebsiteReply> {
        val first = fetch(network, url, control)
        val next = first.location?.let { runCatching { URL(URL(url), it) }.getOrNull() }
        return if (first.status in 300..399 && next?.host == URL(url).host)
            next.toString() to fetch(network, next.toString(), control)
        else null to first
    }

    private fun fetch(network: Network, url: String, control: RunControl): WebsiteReply {
        control.check()
        val connection = network.openConnection(URL(url)) as HttpsURLConnection
        val resource = control.track(Closeable { connection.disconnect() })
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = control.timeoutMillis(UNLOCK_TIMEOUT_MS.toLong())
            connection.readTimeout = control.timeoutMillis(UNLOCK_TIMEOUT_MS.toLong())
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
            )
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")?.take(2048)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readBounded(it) }.orEmpty()
            return WebsiteReply(status, location, body)
        } finally {
            control.release(resource)
        }
    }

    private fun readBounded(input: InputStream): String {
        val bytes = ByteArray(UNLOCK_READ_LIMIT)
        var length = 0
        while (length < bytes.size) {
            val read = input.read(bytes, length, bytes.size - length)
            if (read < 0) break
            length += read
        }
        return String(bytes, 0, length, Charsets.UTF_8)
    }
}

/** The access line under a Service routing row: a result to tap again, or "Check access". */
internal data class RouteAccess(val result: UnlockResult?, val onCheck: () -> Unit)

internal fun unlockText(result: UnlockResult?, language: String): String {
    val region = result?.region?.takeIf { it.isNotEmpty() }?.let { exitCountryLabel(it, language) }
    fun withRegion(id: Int) = tr(id) + (region?.let { " · $it" } ?: "")
    return when (result?.status) {
        null -> tr(R.string.unlock_check)
        UnlockStatus.RUNNING -> tr(R.string.unlock_checking)
        UnlockStatus.AVAILABLE -> withRegion(R.string.unlock_available)
        UnlockStatus.ORIGINALS_ONLY -> withRegion(R.string.unlock_originals_only)
        UnlockStatus.UNAVAILABLE -> withRegion(R.string.unlock_unavailable)
        UnlockStatus.UNKNOWN -> tr(R.string.unlock_unknown)
        UnlockStatus.NEEDS_VPN -> tr(R.string.unlock_needs_vpn)
    }
}

/** Each checkable row's access line; a result checked under other routing is not shown. */
internal fun routeAccess(
    data: AppData,
    unlock: Map<String, UnlockResult>,
    model: AppViewModel,
): (String) -> RouteAccess? {
    val key = websiteRequestKey(data).hashCode()
    return { id ->
        if (id in UNLOCK_SERVICES)
            RouteAccess(unlock[id]?.takeIf { it.key == key }) { model.unlockChecks.check(id, key) }
        else null
    }
}
