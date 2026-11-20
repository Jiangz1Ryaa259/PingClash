// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** The protocols a node can be typed in for; anything else is pasted as a configuration. */
internal enum class NodeProtocol(val type: String, val label: String) {
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("ss", "Shadowsocks"),
    HYSTERIA2("hysteria2", "Hysteria2"),
    TUIC("tuic", "TUIC"),
}

internal val NODE_CIPHERS =
    listOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
    )
internal val NODE_TRANSPORTS = listOf("tcp", "ws", "grpc")
private const val MAX_PORT = 65535
private const val DEFAULT_PORT = "443"

/**
 * One node typed in by hand. Deliberately not saveable: the password stays in memory until it is
 * imported, and then lives only in the encrypted profile like any other.
 */
internal class NodeEntryState {
    var protocol by mutableStateOf(NodeProtocol.VLESS)
    var name by mutableStateOf("")
    var server by mutableStateOf("")
    var port by mutableStateOf(DEFAULT_PORT)
    var uuid by mutableStateOf("")
    var password by mutableStateOf("")
    var cipher by mutableStateOf(NODE_CIPHERS.first())
    var transport by mutableStateOf(NODE_TRANSPORTS.first())
    var tls by mutableStateOf(true)
    var sni by mutableStateOf("")
    var alterId by mutableStateOf("0")
    var obfuscated by mutableStateOf(false)
    var obfsPassword by mutableStateOf("")
    var wsPath by mutableStateOf("")
    var wsHost by mutableStateOf("")
    var grpcService by mutableStateOf("")

    override fun toString() = "NodeEntryState(redacted)"

    val needsUuid
        get() = protocol in setOf(NodeProtocol.VLESS, NodeProtocol.VMESS, NodeProtocol.TUIC)

    val needsPassword
        get() = protocol !in setOf(NodeProtocol.VLESS, NodeProtocol.VMESS)

    val hasTransport
        get() = protocol == NodeProtocol.VLESS || protocol == NodeProtocol.VMESS

    val hasSni
        get() = protocol != NodeProtocol.SHADOWSOCKS && (!hasTransport || tls)

    /** What is missing or wrong, as the message to show; null when the node can be imported. */
    fun problem(): String? {
        val number = port.toIntOrNull()
        return when {
            server.isBlank() -> tr(R.string.node_address_required)
            number == null || number !in 1..MAX_PORT -> tr(R.string.enter_a_valid_port)
            needsUuid && uuid.isBlank() -> tr(R.string.node_secret_required)
            needsPassword && password.isBlank() -> tr(R.string.node_secret_required)
            protocol == NodeProtocol.HYSTERIA2 && obfuscated && obfsPassword.isBlank() ->
                tr(R.string.node_obfuscation_password_required)
            else -> null
        }
    }

    /** The name the profile and node get: the typed one, or the protocol and address. */
    fun displayName(): String = name.trim().ifEmpty { "${protocol.label} ${server.trim()}" }

    /** A profile holding just this node, in the form an imported configuration takes. */
    fun definition(): String = JSONObject().put("proxies", JSONArray().put(node())).toString()

    private fun node(): JSONObject {
        val node =
            JSONObject()
                .put("name", displayName())
                .put("type", protocol.type)
                .put("server", server.trim())
                .put("port", port.toInt())
        when (protocol) {
            NodeProtocol.VLESS -> node.put("uuid", uuid.trim()).transport().put("udp", true)
            NodeProtocol.VMESS ->
                node
                    .put("uuid", uuid.trim())
                    .put("alterId", alterId.toIntOrNull() ?: 0)
                    .put("cipher", "auto")
                    .transport()
                    .put("udp", true)
            NodeProtocol.TROJAN -> node.put("password", password).put("udp", true).sni("sni")
            NodeProtocol.SHADOWSOCKS ->
                node.put("cipher", cipher).put("password", password).put("udp", true)
            NodeProtocol.HYSTERIA2 -> {
                node.put("password", password).sni("sni")
                if (obfuscated) node.put("obfs", "salamander").put("obfs-password", obfsPassword)
            }
            NodeProtocol.TUIC -> node.put("uuid", uuid.trim()).put("password", password).sni("sni")
        }
        return node
    }

    private fun JSONObject.transport(): JSONObject {
        put("network", transport).put("tls", tls)
        when (transport) {
            "ws" -> {
                val path = wsPath.trim().ifEmpty { "/" }
                val opts = JSONObject().put("path", if (path.startsWith("/")) path else "/$path")
                if (wsHost.isNotBlank())
                    opts.put("headers", JSONObject().put("Host", wsHost.trim()))
                put("ws-opts", opts)
            }
            "grpc" ->
                if (grpcService.isNotBlank())
                    put("grpc-opts", JSONObject().put("grpc-service-name", grpcService.trim()))
        }
        return if (tls) sni("servername") else this
    }

    private fun JSONObject.sni(key: String): JSONObject =
        if (sni.isBlank()) this else put(key, sni.trim())
}
