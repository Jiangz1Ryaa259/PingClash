// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.diagnostics.Models

private const val TRAIL_SIZE = 4
private const val NODE_STAGE = 2
private const val WEB_STAGE = 3
private const val FAST_LATENCY_MS = 300L
private const val ELEVATED_LATENCY_MS = 600L

internal fun diagnosticTrail(state: DiagnosticUiState, selectedId: String?): List<TrailTone> {
    val pending = List(TRAIL_SIZE) { TrailTone.PENDING }
    if (selectedId == null || state.nodeId != selectedId) return pending
    val steps = if (state.running) state.steps else state.report?.steps ?: state.steps
    return if (steps.any { it.state in setOf(Models.State.STALE, Models.State.CANCELLED) }) {
        List(TRAIL_SIZE) { TrailTone.STALE }
    } else trailFromSteps(steps, state.running)
}

private fun trailFromSteps(steps: List<Models.Step>, running: Boolean): List<TrailTone> {
    fun evidence(stage: Models.Stage): TrailTone {
        val step = steps.lastOrNull { it.stage == stage } ?: return TrailTone.PENDING
        return when (step.state) {
            Models.State.PASSED -> TrailTone.PASSED
            Models.State.FAILED,
            Models.State.BLOCKED -> TrailTone.FAILED
            Models.State.SKIPPED -> TrailTone.SKIPPED
            else -> TrailTone.PENDING
        }
    }
    val result =
        mutableListOf(
            evidence(Models.Stage.NETWORK),
            evidence(Models.Stage.DNS),
            TrailTone.PENDING,
            TrailTone.PENDING,
        )
    if (steps.any { it.code == Models.Code.GROUP_ROUTE }) result[1] = TrailTone.SKIPPED
    val tcp = evidence(Models.Stage.TCP)
    val tls = evidence(Models.Stage.TLS)
    result[NODE_STAGE] =
        when {
            tls == TrailTone.FAILED || tcp == TrailTone.FAILED -> TrailTone.FAILED
            tcp == TrailTone.PASSED -> TrailTone.PASSED
            else -> TrailTone.PENDING
        }
    applyProxyEvidence(result, steps.lastOrNull { it.stage == Models.Stage.PROXY })
    if (running) {
        val next = result.indexOf(TrailTone.PENDING)
        if (next >= 0) result[next] = TrailTone.CHECKING
    }
    return result
}

private fun applyProxyEvidence(result: MutableList<TrailTone>, proxy: Models.Step?) {
    if (proxy == null) return
    when (proxy.code) {
        Models.Code.PROXY_OK -> {
            val tone =
                if (proxy.state == Models.State.PASSED) TrailTone.PASSED else TrailTone.INCOMPLETE
            result[NODE_STAGE] = tone
            result[WEB_STAGE] = tone
        }
        Models.Code.TARGET_CERTIFICATE,
        Models.Code.PROXY_HTTP_STATUS -> {
            result[NODE_STAGE] = TrailTone.PASSED
            result[WEB_STAGE] = TrailTone.FAILED
        }
        Models.Code.PROXY_CERTIFICATE,
        Models.Code.PROXY_QUIC_TLS,
        Models.Code.PROXY_QUIC_TIMEOUT,
        Models.Code.PROXY_CHANNEL_TIMEOUT,
        Models.Code.PROXY_CHANNEL_FAILED,
        Models.Code.PROXY_CONFIG_INVALID,
        Models.Code.PROXY_DNS -> result[NODE_STAGE] = TrailTone.FAILED
        Models.Code.PROXY_FAILED,
        Models.Code.PROXY_TIMEOUT,
        Models.Code.DEADLINE -> {
            if (result[NODE_STAGE] != TrailTone.FAILED) result[NODE_STAGE] = TrailTone.INCOMPLETE
            result[WEB_STAGE] = TrailTone.INCOMPLETE
        }
        else -> Unit
    }
}

internal fun selectedUrlDelay(data: AppData, tests: ProxyTestState): ProxyDelay? {
    val profile = data.activeProfile
    val name = data.selected?.name
    if (profile == null || name == null) return null
    return tests.values[delayKey(profile.id, name)]
        ?: tests.values.entries
            .firstOrNull {
                it.key.startsWith(profile.id + '\u0000') && it.key.endsWith("\u0000" + name)
            }
            ?.value
}

internal fun dashboardTone(
    data: AppData,
    vpn: VpnUiState,
    diagnosis: DiagnosticUiState,
): TrailTone {
    val trail = diagnosticTrail(diagnosis, data.selectedId)
    return when {
        vpn.stage == VpnPhase.ERROR -> TrailTone.FAILED
        vpn.phase in setOf("connecting", "stopping") -> TrailTone.PENDING
        diagnosis.nodeId != data.selectedId || diagnosis.running -> TrailTone.PENDING
        trail.last() == TrailTone.PASSED -> TrailTone.PASSED
        TrailTone.FAILED in trail -> TrailTone.FAILED
        TrailTone.INCOMPLETE in trail -> TrailTone.INCOMPLETE
        else -> TrailTone.PENDING
    }
}

/** Display thresholds describe this measurement, never a guarantee of network quality. */
internal fun latencyLabel(elapsed: Long): Int =
    when {
        elapsed < FAST_LATENCY_MS -> R.string.ui_latency_fast
        elapsed <= ELEVATED_LATENCY_MS -> R.string.ui_latency_moderate
        else -> R.string.ui_latency_high
    }
