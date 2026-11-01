// SPDX-License-Identifier: GPL-3.0-only
// Resolver/protocol bounds are shared with Android's deadline-controlled probes.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.net.DnsResolver
import android.net.InetAddresses
import android.net.Network
import android.os.CancellationSignal
import dev.pingclash.diagnostics.ProbeFailure
import dev.pingclash.diagnostics.RunControl
import java.io.Closeable
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException

/** Bounded HTTPS responses over the captured app route; no core URLTest or account cookies. */
internal object WebsiteProbe {
    fun run(target: ServiceTarget, timeout: Int, control: RunControl, network: Network): Latency {
        var started = System.nanoTime()
        return runCatching {
                val url = URL(target.url)
                require(url.protocol == "https" && url.userInfo == null)
                resolve(network, url.host, timeout, control)
                // One budget covers warm-up and measurement, so an unreachable site costs one
                // timeout, not two.
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.toLong())
                warmUp(network, url, timeout, control)
                control.check()
                started = System.nanoTime()
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - started).toInt()
                if (remaining <= 0) throw SocketTimeoutException("website_timeout")
                val reply = response(network, url, remaining, control)
                val failure = websiteResponseFailure(target, reply)
                val elapsed =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1)
                if (failure == null) Latency.Measured(elapsed)
                else Latency.Failed(failure, reply.status, elapsed)
            }
            .getOrElse { failure ->
                val stopped = runCatching { control.check() }.exceptionOrNull()
                Latency.Failed(
                    classifyWebsiteFailure(stopped ?: failure),
                    elapsed =
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1),
                )
            }
    }

    /**
     * Opens the connection the measured request reuses. Its status never produces a success
     * verdict; a definite failure (DNS, refused, reset, certificate) ends the probe here, while a
     * slow warm-up only hands the rest of the budget to the measured request.
     */
    private fun warmUp(network: Network, url: URL, timeout: Int, control: RunControl) {
        runCatching { request(network, url, "HEAD", minOf(1000, timeout / 3), control) }
            .onFailure { failure ->
                if (classifyWebsiteFailure(failure) in definiteWebsiteFailures) throw failure
            }
    }

    private val definiteWebsiteFailures =
        setOf(
            WebsiteFailure.CANCELLED,
            WebsiteFailure.DNS,
            WebsiteFailure.REFUSED,
            WebsiteFailure.RESET,
            WebsiteFailure.CERTIFICATE,
        )

    private fun response(
        network: Network,
        initial: URL,
        timeout: Int,
        control: RunControl,
    ): WebsiteReply {
        var url = initial
        var reply = WebsiteReply(0, failure = WebsiteFailure.REDIRECT)
        var finished = false
        var redirects = 0
        while (!finished && redirects < 4) {
            control.check()
            reply = request(network, url, "GET", timeout, control)
            if (reply.status !in setOf(301, 302, 303, 307, 308)) finished = true
            else {
                val next = reply.location?.let { URL(url, it) }
                val failure =
                    when {
                        next == null -> WebsiteFailure.REDIRECT
                        restrictedWebsiteLocation(next) -> WebsiteFailure.RESTRICTED
                        !safeWebsiteRedirect(initial, next) -> WebsiteFailure.REDIRECT
                        else -> null
                    }
                if (failure == null) url = requireNotNull(next)
                else {
                    reply = reply.copy(failure = failure)
                    finished = true
                }
            }
            redirects++
        }
        return if (finished) reply else reply.copy(failure = WebsiteFailure.REDIRECT)
    }

    private fun request(
        network: Network,
        url: URL,
        method: String,
        timeout: Int,
        control: RunControl,
    ): WebsiteReply {
        val connection = network.openConnection(url) as HttpsURLConnection
        val resource = control.track(Closeable { connection.disconnect() })
        var keepWarm = false
        try {
            connection.sslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = control.timeoutMillis(timeout.toLong())
            connection.readTimeout = control.timeoutMillis(timeout.toLong())
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
            )
            if (method == "GET") {
                connection.setRequestProperty("Range", "bytes=0-4095")
                connection.setRequestProperty("Connection", "close")
            }
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")?.take(2048)
            val preview =
                if (method == "GET" && status in 200..299 && status != 204) {
                    connection.inputStream.use { input ->
                        val bytes = readWebsitePreview(input)
                        // A server may ignore Range: never drain a whole homepage to reuse its
                        // socket.
                        connection.disconnect()
                        bytes.toString(Charsets.UTF_8)
                    }
                } else {
                    // Closing the (empty) body returns the socket to the pool for reuse.
                    if (status in 200..299) connection.inputStream.close()
                    else connection.errorStream?.close()
                    ""
                }
            control.check()
            // Any HEAD reply has no body, so its connection stays reusable whatever the status.
            keepWarm = method == "HEAD" && status > 0
            return WebsiteReply(status, location, preview)
        } finally {
            if (!keepWarm) control.release(resource)
        }
    }

    private fun resolve(
        network: Network,
        host: String,
        timeout: Int,
        control: RunControl,
    ): List<InetAddress> {
        if (InetAddresses.isNumericAddress(host))
            return listOf(InetAddresses.parseNumericAddress(host))
        val answer = CompletableFuture<List<InetAddress>>()
        val signal = CancellationSignal()
        val resource =
            control.track(
                Closeable {
                    signal.cancel()
                    answer.cancel(false)
                }
            )
        try {
            DnsResolver.getInstance()
                .query(
                    network,
                    host,
                    DnsResolver.FLAG_EMPTY,
                    Executor { it.run() },
                    signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(addresses: List<InetAddress>, rcode: Int) {
                            if (rcode == 0 && addresses.isNotEmpty()) answer.complete(addresses)
                            else
                                answer.completeExceptionally(
                                    UnknownHostException("website_dns_failed")
                                )
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            answer.completeExceptionally(error)
                        }
                    },
                )
            return answer.get(
                control.timeoutMillis(timeout.toLong()).toLong(),
                TimeUnit.MILLISECONDS,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            control.release(resource)
        }
    }
}

internal fun classifyWebsiteFailure(failure: Throwable): WebsiteFailure {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    return when {
        causes.any { it is ProbeFailure && it.code.name == "CANCELLED" } -> WebsiteFailure.CANCELLED
        causes.any(::isWebsiteTimeout) -> WebsiteFailure.TIMEOUT
        causes.any { it is CertificateException || it is SSLPeerUnverifiedException } ->
            WebsiteFailure.CERTIFICATE
        causes.any { it is UnknownHostException || it is DnsResolver.DnsException } ->
            WebsiteFailure.DNS
        causes.any {
            it is SocketException &&
                (it.message.orEmpty().contains("reset", true) ||
                    it.message.orEmpty().contains("ECONNRESET", true))
        } -> WebsiteFailure.RESET
        causes.any { it is ConnectException } -> WebsiteFailure.REFUSED
        else -> WebsiteFailure.OTHER
    }
}

private fun isWebsiteTimeout(failure: Throwable): Boolean =
    when (failure) {
        is SocketTimeoutException,
        is java.util.concurrent.TimeoutException -> true
        is ProbeFailure -> failure.code.name == "DEADLINE"
        else -> false
    }
