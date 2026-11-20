// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Address ranges.

package dev.pingclash.app

/**
 * Private, loopback, shared, link-local, benchmark (fake-IP), multicast and reserved IPv4 ranges.
 */
private val reservedRanges =
    listOf(
        "0.0.0.0" to 8,
        "10.0.0.0" to 8,
        "100.64.0.0" to 10,
        "127.0.0.0" to 8,
        "169.254.0.0" to 16,
        "172.16.0.0" to 12,
        "192.168.0.0" to 16,
        "198.18.0.0" to 15,
        "224.0.0.0" to 3,
    )

/** Addresses no public server has. */
internal fun reservedAddress(address: String): Boolean =
    if (':' in address) reservedIpv6(address)
    else
        ipv4Value(address)?.let { value ->
            reservedRanges.any { (base, bits) ->
                val mask = (-1L shl (32 - bits)) and 0xffffffffL
                (value and mask) == (requireNotNull(ipv4Value(base)) and mask)
            }
        } == true

/**
 * Unspecified, loopback, IPv4-mapped (judged by the IPv4 inside), unique local (fc00::/7),
 * link-local (fe80::/10), old site-local (fec0::/10), multicast (ff00::/8) and documentation
 * (2001:db8::/32) addresses.
 */
private fun reservedIpv6(address: String): Boolean {
    val bytes =
        runCatching {
                android.net.InetAddresses.parseNumericAddress(address.substringBefore('%')).address
            }
            .getOrNull()
    return when {
        bytes == null -> false
        bytes.size == 4 ->
            reservedAddress(bytes.joinToString(".") { (it.toInt() and 255).toString() })
        else -> reservedIpv6Bytes(bytes.map { it.toInt() and 255 })
    }
}

private fun reservedIpv6Bytes(b: List<Int>): Boolean =
    b.all { it == 0 } ||
        (b.take(15).all { it == 0 } && b[15] == 1) ||
        (b[0] and 0xfe) == 0xfc ||
        (b[0] == 0xfe && (b[1] and 0x80) == 0x80) ||
        b[0] == 0xff ||
        (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)

private fun ipv4Value(address: String): Long? =
    address
        .split('.')
        .map { it.toIntOrNull() }
        .takeIf { parts -> parts.size == 4 && parts.all { it != null && it in 0..255 } }
        ?.fold(0L) { acc, part -> (acc shl 8) or requireNotNull(part).toLong() }
