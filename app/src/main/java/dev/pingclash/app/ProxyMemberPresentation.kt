// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** What a member's details sheet shows about it. */
internal data class ProxyMemberPresentation(
    val name: String,
    val description: String,
    val chosen: Boolean,
    val selectable: Boolean,
    val delay: ProxyDelay?,
    val list: Boolean,
    /** The most consistent node of its group over recent checks ([mostStable]). */
    val stable: Boolean = false,
)

/** What the details sheet may offer for one member of one group. */
private class DetailAbility(state: ProxyPageState, group: LocalGroup, name: String) {
    private val tests = state.tests
    val node = state.nodes[name]
    val key = delayKey(state.profile.id, name, group.name)
    val udp = node?.type in setOf("hysteria", "hysteria2", "tuic")
    private val canTest = !state.locked && tests.memberFinished(state.profile.id, name, group.name)
    private val available = node != null || state.groups[name]?.supported == true
    val url = available && canTest && !tests.running
    val tcp = canTest && node != null && !udp && tests.tcpValues[key]?.state != "running"
    val diagnose = available && canTest
}

/** Long-press details for a member: its latency, TCP, stability and diagnosis. */
@Composable
internal fun ProxyDetailHost(
    state: ProxyPageState,
    target: ProxyDetailTarget,
    model: AppViewModel,
    dismiss: () -> Unit,
    onDiagnosis: () -> Unit,
) {
    val profile = state.profile
    val group =
        state.groups[target.group]
            ?: profile.displayGroups().firstOrNull { it.name == target.group }
    if (group == null || target.name !in group.proxies) return
    val name = target.name
    val can = DetailAbility(state, group, name)
    ProxyMemberDetails(
        ProxyMemberPresentation(
            name,
            profile.proxyDescription(name).ifBlank { tr(R.string.member_unavailable) },
            false,
            false,
            state.reach(name, group.name),
            false,
        ),
        stabilityOf(state.tests.historyFor(profile.id, name, group.name)),
        state.tests.tcpValues[can.key],
        can.udp,
        ProxyDetailActions(
            dismiss = dismiss,
            urlTest =
                { model.testProxies(profile, listOf(name), group.name).let {} }.takeIf { can.url },
            tcpTest = { model.testTcpMember(profile, name, group.name) }.takeIf { can.tcp },
            diagnose =
                {
                        dismiss()
                        model.doctorFor(profile.id, name)
                        onDiagnosis()
                    }
                    .takeIf { can.diagnose },
        ),
    )
}

@Composable
internal fun ProxySymbol(
    kind: String,
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalContentColor.current,
) {
    AppGlyph(
        when (kind) {
            "check" -> PcIcon.Check
            "menu" -> PcIcon.More
            "down" -> PcIcon.ChevronDown
            "right" -> PcIcon.ChevronRight
            else -> PcIcon.Bolt
        },
        modifier,
        color,
    )
}
