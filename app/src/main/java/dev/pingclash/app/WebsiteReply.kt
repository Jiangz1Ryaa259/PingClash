// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.io.InputStream
import java.net.URL
import java.util.Locale

/** Response bodies/redirects stay local to one bounded probe and are never output. */
internal data class WebsiteReply(
    val status: Int,
    val location: String? = null,
    val preview: String = "",
    val failure: WebsiteFailure? = null,
) {
    override fun toString() = "WebsiteReply(status=$status)"
}

/**
 * Whether this network can reach [target]: any HTTP answer over a verified TLS connection says it
 * can, whatever the status (a redirect to sign-in, 403, 429 or a bot challenge a browser passes all
 * mean the way is open). Only an explicit refusal of this location is not success: HTTP 451, or an
 * AI service's region-unavailable page or redirect.
 */
internal fun websiteResponseFailure(target: ServiceTarget, reply: WebsiteReply): WebsiteFailure? {
    val redirect =
        reply.location?.let { location ->
            runCatching { URL(URL(target.url), location) }.getOrNull()
        }
    return when {
        reply.failure != null -> reply.failure
        reply.status == HTTP_UNAVAILABLE_FOR_LEGAL_REASONS -> WebsiteFailure.RESTRICTED
        redirect != null && restrictedWebsiteLocation(redirect) -> WebsiteFailure.RESTRICTED
        restrictedWebsiteBody(target, reply.preview) -> WebsiteFailure.RESTRICTED
        else -> null
    }
}

internal fun restrictedWebsiteLocation(url: URL): Boolean {
    val path = url.path.lowercase(Locale.ROOT)
    return listOf("app-unavailable-in-region", "unsupported-country", "unsupported_region")
        .any(path::contains)
}

/** Services that answer an unsupported region with a page of their own. */
internal fun regionSensitiveWebsite(target: ServiceTarget) =
    target.id in setOf("claude", "chatgpt", "gemini")

private fun restrictedWebsiteBody(target: ServiceTarget, preview: String): Boolean {
    if (!regionSensitiveWebsite(target)) return false
    val text = preview.lowercase(Locale.ROOT)
    return listOf(
            "claude is only available in certain regions",
            "unsupported country",
            "unsupported_country",
            "app-unavailable-in-region",
        )
        .any(text::contains)
}

internal fun readWebsitePreview(input: InputStream): ByteArray {
    val bytes = ByteArray(MAX_WEBSITE_PREVIEW)
    var length = 0
    while (length < bytes.size) {
        val read = input.read(bytes, length, bytes.size - length)
        if (read < 0) break
        length += read
    }
    return bytes.copyOf(length)
}

private const val MAX_WEBSITE_PREVIEW = 4096

private const val HTTP_UNAVAILABLE_FOR_LEGAL_REASONS = 451
