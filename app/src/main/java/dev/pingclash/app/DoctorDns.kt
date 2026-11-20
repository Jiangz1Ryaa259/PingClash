// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // DNS wire format.

package dev.pingclash.app

import android.net.Network
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * How the two answers relate. Only a private or reserved answer from the network, where the
 * reference gives a public one, is tampering; a network that gave no answer may just have timed
 * out, so that is not compared.
 */
internal fun compareDns(system: List<String>, configured: List<String>): DnsVerdict =
    when {
        system.isEmpty() || configured.isEmpty() -> DnsVerdict.UNKNOWN
        system.any(::reservedAddress) && configured.none(::reservedAddress) -> DnsVerdict.TAMPERED
        system.any { it in configured } -> DnsVerdict.SAME
        else -> DnsVerdict.DIFFERENT
    }

/**
 * One A query over HTTPS (RFC 8484) to the encrypted DNS [url] from Settings, on [network], not
 * through the VPN. The certificate is checked as usual, so a network that intercepts plain DNS
 * cannot answer for it.
 */
internal fun queryHttps(network: Network, host: String, url: String): List<String> {
    val target = java.net.URL(url.substringBefore('#').trim())
    require(target.protocol == "https")
    val connection = network.openConnection(target) as javax.net.ssl.HttpsURLConnection
    try {
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        // A redirect would send the query somewhere the user did not set.
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/dns-message")
        connection.setRequestProperty("Accept", "application/dns-message")
        val query = dnsQuery(0, host)
        connection.outputStream.use { it.write(query) }
        if (connection.responseCode != 200) return emptyList()
        // A DNS message is at most 64 KiB; a wrong URL that answers with a large page is cut off
        // there and read as no answer, never loaded whole.
        val reply = connection.inputStream.use { readAtMost(it, MAX_DNS_MESSAGE + 1) }
        return if (reply.size > MAX_DNS_MESSAGE) emptyList() else dnsAnswers(reply, 0)
    } finally {
        connection.disconnect()
    }
}

/** Up to [limit] bytes of [input]; whatever follows is left unread. */
internal fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** The largest DNS message there is. */
private const val MAX_DNS_MESSAGE = 65_535

/** One A query over UDP to [server] ("ip" or "ip:port"), on [network], not through the VPN. */
internal fun queryServer(network: Network, host: String, server: String): List<String> {
    val (address, port) = dnsServer(server)
    DatagramSocket().use { socket ->
        network.bindSocket(socket)
        socket.soTimeout = 3000
        val id = (System.nanoTime() and 0xffff).toInt()
        val query = dnsQuery(id, host)
        socket.send(DatagramPacket(query, query.size, InetSocketAddress(address, port)))
        val buffer = ByteArray(1500)
        val reply = DatagramPacket(buffer, buffer.size)
        socket.receive(reply)
        return dnsAnswers(buffer.copyOf(reply.length), id)
    }
}

private fun dnsServer(raw: String): Pair<InetAddress, Int> {
    val value = raw.trim()
    if (android.net.InetAddresses.isNumericAddress(value))
        return android.net.InetAddresses.parseNumericAddress(value) to 53
    val uri = java.net.URI("dns://$value")
    return android.net.InetAddresses.parseNumericAddress(uri.host.trim('[', ']')) to
        (if (uri.port > 0) uri.port else 53)
}

/** A standard recursive query for the A record of [host]. */
internal fun dnsQuery(id: Int, host: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
    host.trimEnd('.').split('.').forEach { label ->
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..63)
        out.write(bytes.size)
        out.write(bytes)
    }
    out.write(byteArrayOf(0, 0, 1, 0, 1))
    return out.toByteArray()
}

/** The IPv4 addresses in a reply to query [id]; empty for anything else. */
@Suppress("ReturnCount") // Each truncated part of a reply ends the parse.
internal fun dnsAnswers(reply: ByteArray, id: Int): List<String> {
    if (reply.size < 12 || (reply.u16(0)) != id) return emptyList()
    val questions = reply.u16(4)
    val answers = reply.u16(6)
    var at = 12
    repeat(questions) { at = skipName(reply, at) + 4 }
    val found = mutableListOf<String>()
    repeat(answers) {
        at = skipName(reply, at)
        if (at + 10 > reply.size) return found
        val type = reply.u16(at)
        val length = reply.u16(at + 8)
        at += 10
        if (at + length > reply.size) return found
        if (type == 1 && length == 4)
            found += (0 until 4).joinToString(".") { (reply[at + it].toInt() and 255).toString() }
        at += length
    }
    return found
}

private fun ByteArray.u16(at: Int) =
    ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)

@Suppress("ReturnCount") // The end of a name, a pointer, or the end of the reply.
private fun skipName(reply: ByteArray, start: Int): Int {
    var at = start
    while (at < reply.size) {
        val length = reply[at].toInt() and 255
        if (length == 0) return at + 1
        if (length and 0xc0 == 0xc0) return at + 2
        at += length + 1
    }
    return reply.size
}
