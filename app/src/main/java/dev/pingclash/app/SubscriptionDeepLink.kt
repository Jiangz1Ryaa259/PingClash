// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.net.URI
import java.net.URLDecoder

/** Ephemeral input: never place the source URL in saved state, logs or errors. */
internal class SubscriptionDeepLink
private constructor(
    val source: String,
    val host: String,
    val name: String,
    val suggestedName: String,
) {
    override fun toString() = "SubscriptionDeepLink(redacted)"

    companion object {
        private const val MAX_LINK_LENGTH = 16384
        private const val MAX_NAME_CODEPOINTS = 64L

        fun parse(raw: String?): SubscriptionDeepLink? =
            runCatching {
                    require(raw != null && raw.length <= MAX_LINK_LENGTH)
                    val link = URI(raw)
                    require(link.scheme == "clash" && link.host == "install-config")
                    require(link.rawUserInfo == null && link.port == -1 && link.rawFragment == null)
                    require(link.rawPath.isNullOrEmpty() || link.rawPath == "/")
                    val fields = linkedMapOf<String, String>()
                    requireNotNull(link.rawQuery).split('&').forEach { pair ->
                        val parts = pair.split('=', limit = 2)
                        require(parts.size == 2)
                        val key = decode(parts[0])
                        val value = decode(parts[1])
                        // Other clients and panels append their own hints; ignore any
                        // well-formed extra parameter, but never a repeated url/name.
                        if (key in setOf("url", "name")) {
                            require(key !in fields)
                            fields[key] = value
                        }
                    }
                    val source = requireNotNull(fields["url"])
                    require(validHttps(source))
                    val host = requireNotNull(URI(source).host)
                    val suggested = cleanName(fields["name"].orEmpty())
                    val name = suggested.ifBlank { tr(R.string.deep_link_default_name) }
                    SubscriptionDeepLink(source, host, name, suggested)
                }
                .getOrNull()

        private fun decode(value: String): String =
            URLDecoder.decode(value, "UTF-8").also { require('\uFFFD' !in it) }

        internal fun cleanName(value: String): String {
            val clean = StringBuilder()
            value
                .codePoints()
                .filter { code ->
                    !Character.isISOControl(code) &&
                        Character.getType(code) !in
                            setOf(
                                Character.FORMAT.toInt(),
                                Character.SURROGATE.toInt(),
                                Character.LINE_SEPARATOR.toInt(),
                                Character.PARAGRAPH_SEPARATOR.toInt(),
                            )
                }
                .limit(MAX_NAME_CODEPOINTS)
                .forEach { clean.appendCodePoint(it) }
            return clean.toString().trim()
        }
    }
}
