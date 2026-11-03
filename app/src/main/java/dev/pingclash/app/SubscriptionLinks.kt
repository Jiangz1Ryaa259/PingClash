// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

// Pasted subscription links are often looser than RFC 3986, and java.net.URI rejects or half-reads
// them: `|` between subconverter sources, braces or spaces in a token, a non-ASCII domain, a host
// with an underscore, a `#name` the provider appended. These helpers turn such a link into the
// exact URI it denotes. Only https is accepted, and never with user info.

/** Characters a URI may carry as they are; anything else is percent-encoded. */
private const val URI_SAFE = "-._~:/?[]@!$&'()*+,;="
private const val HEX = "0123456789ABCDEFabcdef"
private const val MAX_LINK = 4096
private const val MAX_PORT = 65535
private const val HTTPS_DEFAULT_PORT = 443
private const val ASCII_END = 128
private const val BYTE_MASK = 0xFF

// Hosts java.net.URI leaves unparsed (underscores), with an optional port.
private val REGISTRY_AUTHORITY = Regex("([A-Za-z0-9_-]+\\.)*[A-Za-z0-9_-]+(:[0-9]{1,5})?")

/** A pasted link as the exact https URI it denotes, and the name its `#fragment` carried. */
internal class NormalizedLink(val source: String, val label: String)

/** Null when [raw] is not an https link that can be fetched safely. */
internal fun normalizedSubscriptionLink(raw: String): NormalizedLink? {
    val text = raw.trim()
    val scheme = "https://"
    val valid = text.length <= MAX_LINK && text.startsWith(scheme, ignoreCase = true)
    return text
        .takeIf { valid }
        ?.substring(scheme.length)
        ?.let { rest ->
            val link = rest.substringBefore('#')
            val authority = link.takeWhile { it != '/' && it != '?' }
            asciiAuthority(authority)?.let { host ->
                val source = scheme + host + encodeLoose(link.substring(authority.length))
                val label =
                    SubscriptionDeepLink.cleanName(decodeLabel(rest.substringAfter('#', "")))
                NormalizedLink(source, label).takeIf { validHttps(source) }
            }
        }
}

/** The authority with a non-ASCII domain in its ASCII form; null when it carries user info. */
private fun asciiAuthority(authority: String): String? =
    authority
        .takeIf { it.isNotEmpty() && '@' !in it }
        ?.let { value ->
            if (value.all { it.code < ASCII_END }) value
            else
                runCatching {
                        val host = value.substringBeforeLast(':').takeIf { ':' in value } ?: value
                        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) + value.removePrefix(host)
                    }
                    .getOrNull()
        }

/** Percent-encodes what a URI cannot carry, leaving valid escapes as they are. */
private fun encodeLoose(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val escape =
            char == '%' &&
                value.getOrNull(index + 1)?.let { it in HEX } == true &&
                value.getOrNull(index + 2)?.let { it in HEX } == true
        when {
            escape || (char.code < ASCII_END && (char.isLetterOrDigit() || char in URI_SAFE)) ->
                append(char)
            else ->
                value
                    .substring(index, index + Character.charCount(value.codePointAt(index)))
                    .toByteArray(Charsets.UTF_8)
                    .forEach { append('%').append("%02X".format(it.toInt() and BYTE_MASK)) }
        }
        index += Character.charCount(value.codePointAt(index))
    }
}

private fun decodeLabel(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

/**
 * The host and port an https [uri] connects to. java.net.URI leaves a host with an underscore
 * unparsed, so such hosts are read from the raw authority.
 */
internal fun httpsAuthority(uri: URI): Pair<String, Int>? {
    val parsed =
        uri.host?.let { it to uri.port }
            ?: uri.rawAuthority
                ?.takeIf { REGISTRY_AUTHORITY.matches(it) }
                ?.let {
                    it.substringBefore(':') to (it.substringAfter(':', "").toIntOrNull() ?: -1)
                }
    return parsed
        ?.takeIf { (host, port) -> host.isNotBlank() && (port == -1 || port in 1..MAX_PORT) }
        ?.let { (host, port) -> host to if (port == -1) HTTPS_DEFAULT_PORT else port }
}
