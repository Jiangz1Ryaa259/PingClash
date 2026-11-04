// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.net.URLDecoder
import java.util.Base64

/**
 * Display metadata only. Names come from the provider's headers first; without them (signed,
 * expiring links on object storage or a CDN send none) from a config file name in the link's path,
 * then the subscription host. Never from the query, and never a path segment that looks like a
 * token.
 */
internal object SubscriptionNames {
    private const val MAX_HEADER = 4096
    private const val MAX_DISPLAY = 100
    private const val EXTENDED_PARTS = 3

    fun fromHeaders(title: String?, disposition: String?): String? =
        headerTitle(title) ?: fileTitle(disposition)

    fun choose(explicit: String, suggested: String?, fallback: String): String =
        clean(explicit) ?: clean(suggested.orEmpty()) ?: fallback

    fun clean(value: String): String? {
        val text = SubscriptionDeepLink.cleanName(value).take(MAX_DISPLAY)
        return (if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text)
            .takeIf(String::isNotBlank)
    }

    private fun headerTitle(value: String?): String? {
        if (value == null || value.length > MAX_HEADER) return null
        val raw =
            if (value.startsWith("base64:", true))
                runCatching {
                        String(
                            Base64.getDecoder().decode(value.substringAfter(':').trim()),
                            Charsets.UTF_8,
                        )
                    }
                    .getOrNull()
            else value
        return raw?.let(::clean)
    }

    private fun fileTitle(header: String?): String? {
        if (header == null || header.length > MAX_HEADER) return null
        val encoded =
            Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.trim('"')
        val extended =
            encoded?.let { value ->
                val pieces = value.split('\'', limit = EXTENDED_PARTS)
                if (pieces.size == EXTENDED_PARTS && pieces[0].equals("UTF-8", true))
                    runCatching { URLDecoder.decode(pieces[2].replace("+", "%2B"), "UTF-8") }
                        .getOrNull()
                else null
            }
        val plain =
            Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))").find(header)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2].trim() }
            }
        return file(extended ?: plain.orEmpty())
    }

    /**
     * A name for a link whose server sent none: a config file name in [final] (where redirects
     * ended) or [original], else [original]'s host.
     */
    fun fromLink(final: java.net.URI, original: java.net.URI): String? =
        pathFile(final) ?: pathFile(original) ?: hostName(original)

    /** "sub.example.com" without "www."; none for an IP address or localhost. */
    private fun hostName(link: java.net.URI): String? {
        val host = link.host?.trim('[', ']')?.lowercase() ?: return null
        val literal = ':' in host || host.all { it.isDigit() || it == '.' } || host == "localhost"
        return if (literal) null else clean(host.removePrefix("www."))
    }

    private fun pathFile(link: java.net.URI): String? {
        val leaf = link.rawPath.orEmpty().substringAfterLast('/')
        val name = runCatching { URLDecoder.decode(leaf.replace("+", "%2B"), "UTF-8") }.getOrNull()
        val stem =
            name?.let { CONFIG_FILE.find(it)?.groupValues?.get(1) }?.takeUnless(::looksLikeToken)
        return stem?.let(::clean)
    }

    private val CONFIG_FILE = Regex("(?i)^(.+)\\.(yaml|yml|json|txt|conf)$")

    /** Long runs of letters and digits, hex or UUIDs: access tokens, never shown as a name. */
    private fun looksLikeToken(value: String): Boolean {
        val plain = value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        val ascii = value.all { it.code < ASCII_LIMIT }
        return plain &&
            ascii &&
            (value.length >= TOKEN_LENGTH ||
                value.length >= MIXED_TOKEN_LENGTH &&
                    value.any(Char::isDigit) &&
                    value.any(Char::isLetter))
    }

    private const val ASCII_LIMIT = 128
    private const val TOKEN_LENGTH = 24
    private const val MIXED_TOKEN_LENGTH = 12

    fun file(value: String): String? {
        val leaf = value.replace('\\', '/').substringAfterLast('/')
        val title = leaf.replace(Regex("(?i)\\.(yaml|yml|json|txt)$"), "")
        return clean(title)
    }
}

internal class SubscriptionInput(val source: String, val suggestedName: String) {
    override fun toString() = "SubscriptionInput(redacted)"
}

internal fun subscriptionInput(raw: String): SubscriptionInput? {
    val text = raw.trim()
    return normalizedSubscriptionLink(text)?.let { SubscriptionInput(it.source, it.label) }
        ?: SubscriptionDeepLink.parse(text)?.let { SubscriptionInput(it.source, it.suggestedName) }
}
