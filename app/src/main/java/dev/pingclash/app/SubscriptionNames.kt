// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.net.URLDecoder
import java.util.Base64

/** Display metadata only: never infer a title from a credential-bearing URL path/query. */
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
    if (validHttps(text)) return SubscriptionInput(text, "")
    return SubscriptionDeepLink.parse(text)?.let { SubscriptionInput(it.source, it.suggestedName) }
}
