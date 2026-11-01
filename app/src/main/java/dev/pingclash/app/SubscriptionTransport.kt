// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Retain public negotiation headers, never authorization or arbitrary custom headers across
 * origins.
 */
internal fun redirectedSubscriptionHeaders(
    headers: Map<String, String>,
    from: URI,
    to: URI,
): Map<String, String> {
    val same = from.host.equals(to.host, true) && httpsPort(from) == httpsPort(to)
    return if (same) headers
    else
        headers.filterKeys {
            it.lowercase(java.util.Locale.ROOT) in setOf("user-agent", "accept", "accept-language")
        }
}

private fun httpsPort(uri: URI) = uri.port.takeIf { it >= 0 } ?: HTTPS_PORT

private const val HTTPS_PORT = 443

internal fun subscriptionRedirect(from: URI, location: String?): URI =
    runCatching {
            from.resolve(requireNotNull(location)).also { require(validHttps(it.toString())) }
        }
        .getOrElse { throw SubscriptionTransferFailure("provider_redirect") }

internal data class SubscriptionReadLimit(
    val bytes: Int = MAX_FEED_BYTES,
    val code: String = "provider_size_limit",
    val binary: Boolean = false,
) {
    init {
        require(bytes in 1..ProviderBodyCache.RULE_BYTES)
    }
}

internal fun subscriptionBody(
    connection: HttpsURLConnection,
    limit: SubscriptionReadLimit,
): String =
    try {
        if (connection.contentLengthLong > limit.bytes)
            throw SubscriptionTransferFailure(limit.code)
        val raw = connection.inputStream.use { it.readBytesLimited(limit.bytes) }
        if (limit.binary) ProviderBodyCache.packBinary(raw) else raw.toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw SubscriptionTransferFailure(limit.code)
    }

internal fun requireSubscriptionSuccess(status: Int) {
    if (status != SUCCESS_STATUS)
        throw SubscriptionHttpFailure(status.coerceIn(MIN_STATUS, MAX_STATUS))
}

private const val SUCCESS_STATUS = 200
private const val MIN_STATUS = 100
private const val MAX_STATUS = 599
internal const val MAX_FEED_BYTES = 2 * 1024 * 1024

internal fun validateSubscriptionFetch(source: String, userAgent: String) {
    if (!validSubscriptionUa(userAgent)) throw SettingsFailure(tr(R.string.subscription_ua_invalid))
    require(validHttps(source)) { tr(R.string.subscriptions_require_an_https_link_withou) }
}

internal fun checkSubscriptionDeadline(deadline: Long, expired: Boolean = false) {
    if (expired || System.nanoTime() >= deadline)
        throw java.net.SocketTimeoutException("subscription_deadline")
}

internal fun downloadProvider(
    spec: org.json.JSONObject,
    userAgent: String,
    deadline: Long,
): String {
    return runCatching {
            val headers =
                spec.getJSONObject("headers").let { h ->
                    h.keys().asSequence().associateWith(h::getString)
                }
            val remaining =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw java.net.SocketTimeoutException("provider_deadline")
            val appLimit =
                if (spec.getString("kind") == "rule") ProviderBodyCache.RULE_BYTES
                else MAX_FEED_BYTES
            val declared = spec.optInt("sizeLimit").takeIf { it > 0 }
            val maximum = minOf(appLimit, declared ?: appLimit)
            val code =
                if (declared != null && declared <= appLimit) "provider_declared_size_limit"
                else "provider_size_limit"
            val content =
                SubscriptionDownloader.fetchResponse(
                        spec.getString("url"),
                        userAgent,
                        headers,
                        remaining.coerceAtMost(PROVIDER_TIMEOUT_MILLIS).toInt(),
                        SubscriptionReadLimit(maximum, code, spec.optString("format") == "mrs"),
                    )
                    .content
            ProviderBodyCache.pack(content)
        }
        .getOrElse { throw providerTransportException(spec, it) }
}

private const val PROVIDER_TIMEOUT_MILLIS = 30_000L

private fun providerTransportException(spec: org.json.JSONObject, failure: Throwable): Throwable =
    when (failure) {
        is java.io.IOException ->
            providerDownloadFailure(spec.getInt("index"), spec.getString("kind"), failure)
        is IllegalArgumentException ->
            ProviderFailure(spec.getInt("index"), spec.getString("kind"), "provider_invalid")
        is SecurityException ->
            ProviderFailure(spec.getInt("index"), spec.getString("kind"), "provider_network")
        else -> failure // Cancellation and fatal/programming failures are not relabeled as network
    // errors.
    }
