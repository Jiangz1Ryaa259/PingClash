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

internal fun websiteResponseFailure(target: ServiceTarget, reply: WebsiteReply): WebsiteFailure? =
    when {
        reply.failure != null -> reply.failure
        reply.status !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> WebsiteFailure.HTTP
        target.id in setOf("google", "youtube") && reply.status != HTTP_NO_CONTENT ->
            WebsiteFailure.RESPONSE
        restrictedWebsiteBody(target, reply.preview) -> WebsiteFailure.RESTRICTED
        else -> null
    }

internal fun restrictedWebsiteLocation(url: URL): Boolean {
    val path = url.path.lowercase(Locale.ROOT)
    return listOf("app-unavailable-in-region", "unsupported-country", "unsupported_region")
        .any(path::contains)
}

/** No cleartext downgrade, userinfo, or arbitrary cross-site redirect requests. */
internal fun safeWebsiteRedirect(initial: URL, next: URL): Boolean {
    val domain = initial.host.lowercase(Locale.ROOT).removePrefix("www.")
    val host = next.host.lowercase(Locale.ROOT)
    return next.protocol == "https" &&
        next.userInfo == null &&
        (host == domain || host.endsWith(".$domain")) &&
        next.httpsPort() == initial.httpsPort()
}

private fun URL.httpsPort() = if (port < 0) HTTPS_PORT else port

private fun restrictedWebsiteBody(target: ServiceTarget, preview: String): Boolean {
    val text = preview.lowercase(Locale.ROOT)
    val challenge = text.contains("<title>just a moment") || text.contains("cf-chl-")
    val regionService = target.id in setOf("claude", "chatgpt", "gemini")
    val regionPage =
        listOf(
                "claude is only available in certain regions",
                "unsupported country",
                "unsupported_country",
                "app-unavailable-in-region",
            )
            .any(text::contains)
    return challenge || (regionService && regionPage)
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

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val HTTP_NO_CONTENT = 204
private const val HTTPS_PORT = 443
