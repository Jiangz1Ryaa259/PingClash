// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Type a node in by hand: the protocol, where it is, and its credentials, then save it as a local
 * profile holding that node. Fields follow the protocol; Back leaves without saving.
 */
@Composable
internal fun NodeEntryPage(
    busy: Boolean,
    issue: String?,
    onDismiss: () -> Unit,
    onSubmit: (name: String, definition: String) -> Unit,
) {
    val node = remember { NodeEntryState() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(issue) { if (issue != null) error = issue }
    androidx.activity.compose.BackHandler { onDismiss() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("node-entry"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NodeEntryHeader(onDismiss)
            NodeWhere(node, busy) { error = null }
            NodeCredentials(node, busy)
            error?.let { InputIssue(it) }
            GlassButton(
                {
                    error = node.problem()
                    if (error == null) onSubmit(node.displayName(), node.definition())
                },
                Modifier.fillMaxWidth().height(54.dp).testTag("node-save"),
                enabled = !busy,
            ) {
                Text(tr(R.string.save))
            }
        }
    }
}

/** The protocol, an optional name, and where the node is. */
@Composable
private fun NodeWhere(node: NodeEntryState, busy: Boolean, onProtocol: () -> Unit) {
    SettingsSection {
        PopUpChoice(
            tr(R.string.node_protocol),
            node.protocol.name,
            NodeProtocol.entries.map { it.name to it.label },
            !busy,
        ) {
            node.protocol = NodeProtocol.valueOf(it)
            onProtocol()
        }
        RowDivider(underIcons = false)
        TextFieldRow(tr(R.string.profile_name), node.name, !busy, tr(R.string.ui_optional)) {
            if (it.length <= MAX_NAME) node.name = it
        }
    }
    SettingsSection {
        TextFieldRow(tr(R.string.node_address), node.server, !busy, "example.com") {
            if (it.length <= MAX_FIELD) node.server = it.trim()
        }
        RowDivider(underIcons = false)
        TextFieldRow(tr(R.string.port), node.port, !busy) {
            if (it.length <= PORT_DIGITS && it.all(Char::isDigit)) node.port = it
        }
    }
}

/** The fields this protocol needs, in the order the provider usually lists them. */
@Composable
private fun NodeCredentials(node: NodeEntryState, busy: Boolean) {
    SettingsSection { NodeSecrets(node, busy) }
    if (node.hasTransport)
        SettingsSection {
            PopUpChoice(
                tr(R.string.node_transport),
                node.transport,
                listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC"),
                !busy,
            ) {
                node.transport = it
            }
            NodeTransportOptions(node, busy)
            RowDivider(underIcons = false)
            SettingSwitch("TLS", node.tls, !busy) { node.tls = it }
        }
    if (node.hasSni || node.protocol == NodeProtocol.HYSTERIA2)
        SettingsSection { NodeTls(node, busy) }
}

/** WebSocket's path and Host, gRPC's service name: what the provider lists with the transport. */
@Composable
private fun NodeTransportOptions(node: NodeEntryState, busy: Boolean) {
    when (node.transport) {
        "ws" -> {
            RowDivider(underIcons = false)
            TextFieldRow(tr(R.string.node_ws_path), node.wsPath, !busy, "/") {
                if (it.length <= MAX_FIELD) node.wsPath = it
            }
            RowDivider(underIcons = false)
            TextFieldRow("Host", node.wsHost, !busy, tr(R.string.ui_optional)) {
                if (it.length <= MAX_FIELD) node.wsHost = it
            }
        }
        "grpc" -> {
            RowDivider(underIcons = false)
            TextFieldRow(
                tr(R.string.node_grpc_service),
                node.grpcService,
                !busy,
                tr(R.string.ui_optional),
            ) {
                if (it.length <= MAX_FIELD) node.grpcService = it
            }
        }
    }
}

@Composable
private fun NodeSecrets(node: NodeEntryState, busy: Boolean) {
    if (node.needsUuid) {
        TextFieldRow("UUID", node.uuid, !busy) { if (it.length <= MAX_FIELD) node.uuid = it }
        if (node.needsPassword || node.protocol == NodeProtocol.VMESS) RowDivider(false)
    }
    if (node.protocol == NodeProtocol.VMESS)
        TextFieldRow("Alter ID", node.alterId, !busy) {
            if (it.length <= PORT_DIGITS && it.all(Char::isDigit)) node.alterId = it
        }
    if (node.protocol == NodeProtocol.SHADOWSOCKS) {
        PopUpChoice(tr(R.string.node_cipher), node.cipher, NODE_CIPHERS.map { it to it }, !busy) {
            node.cipher = it
        }
        RowDivider(underIcons = false)
    }
    if (node.needsPassword)
        TextFieldRow(tr(R.string.node_password), node.password, !busy) {
            if (it.length <= MAX_FIELD) node.password = it
        }
}

@Composable
private fun NodeTls(node: NodeEntryState, busy: Boolean) {
    if (node.hasSni)
        TextFieldRow("SNI", node.sni, !busy, tr(R.string.ui_optional)) {
            if (it.length <= MAX_FIELD) node.sni = it.trim()
        }
    if (node.protocol != NodeProtocol.HYSTERIA2) return
    if (node.hasSni) RowDivider(underIcons = false)
    SettingSwitch(tr(R.string.node_obfuscation), node.obfuscated, !busy) { node.obfuscated = it }
    if (node.obfuscated) {
        RowDivider(underIcons = false)
        TextFieldRow(tr(R.string.node_obfuscation_password), node.obfsPassword, !busy) {
            if (it.length <= MAX_FIELD) node.obfsPassword = it
        }
    }
}

@Composable
private fun NodeEntryHeader(onDismiss: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = tr(R.string.back)
            Row(
                Modifier.fluidClickable(onClick = onDismiss)
                    .testTag("node-back")
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppGlyph(BACK_GLYPH, Modifier.size(22.dp), pageInk().accent)
                Text(tr(R.string.profiles), color = pageInk().accent)
            }
            Box(Modifier.weight(1f))
        }
        PageHeading(tr(R.string.import_node))
    }
}

private const val MAX_NAME = 100
private const val MAX_FIELD = 256
private const val PORT_DIGITS = 5
