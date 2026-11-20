// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.diagnostics.Models

private const val SECONDS_PER_MINUTE = 60L

/**
 * One plain conclusion from the checks, most basic cause first: no node; the phone's network; the
 * plan; the clock; an unfinished check; then the node (a tampered DNS answer, a network that does
 * not reach the internet, or the stage it failed at); the VPN being off; then the websites. The
 * other nodes are compared in the rows, never in the answer: it only names this node's problem.
 */
internal fun doctorFinding(evidence: DoctorEvidence): DoctorFinding {
    val steps = evidence.steps
    return when {
        !evidence.selected ->
            DoctorFinding(R.string.doctor_cause_no_node, R.string.doctor_next_choose, "NO_NODE")
        else -> phoneFinding(evidence) ?: nodeOrRouteFinding(evidence, steps)
    }
}

/** What the phone, the plan or the clock say before the node is looked at. */
private fun phoneFinding(evidence: DoctorEvidence): DoctorFinding? {
    val phone = evidence.phone
    val plan = evidence.plan
    val clock = evidence.clock
    return when {
        phone?.offline == true ->
            DoctorFinding(R.string.doctor_cause_network, R.string.doctor_next_network, "NO_NETWORK")
        phone?.captive == true ->
            DoctorFinding(R.string.doctor_cause_captive, R.string.doctor_next_captive, "CAPTIVE")
        plan?.expired == true ->
            DoctorFinding(R.string.doctor_cause_expired, R.string.doctor_next_plan, "PLAN_EXPIRED")
        plan?.usedUp == true ->
            DoctorFinding(R.string.doctor_cause_used_up, R.string.doctor_next_plan, "PLAN_USED_UP")
        clock?.off == true ->
            DoctorFinding(
                R.string.doctor_cause_clock,
                R.string.doctor_next_clock,
                "CLOCK_${clockLabel(clock.skewSeconds ?: 0)}",
                clockMinutes(clock.skewSeconds ?: 0).toString(),
            )
        else -> null
    }
}

private fun nodeOrRouteFinding(evidence: DoctorEvidence, steps: List<Models.Step>?): DoctorFinding {
    if (steps.isNullOrEmpty() || steps.any { it.state in INCOMPLETE })
        return DoctorFinding(
            R.string.doctor_cause_incomplete,
            R.string.doctor_next_again,
            "INCOMPLETE",
        )
    val node = nodeFinding(steps)
    return when {
        node != null -> comparedFinding(evidence, steps, node)
        !evidence.vpnActive ->
            DoctorFinding(R.string.doctor_cause_vpn_off, R.string.doctor_next_connect, "VPN_OFF")
        else -> websiteFinding(evidence)
    }
}

/** A failed node, read against the DNS answers and the other nodes. */
private fun comparedFinding(
    evidence: DoctorEvidence,
    steps: List<Models.Step>,
    node: DoctorFinding,
): DoctorFinding {
    val failed = steps.first { it.state == Models.State.FAILED }.stage
    return when {
        evidence.dns?.verdict == DnsVerdict.TAMPERED &&
            failed in setOf(Models.Stage.DNS, Models.Stage.TCP, Models.Stage.TLS) ->
            DoctorFinding(
                R.string.doctor_cause_dns_tampered,
                R.string.doctor_next_dns_tampered,
                "DNS_TAMPERED",
            )
        evidence.phone?.validated == false ->
            DoctorFinding(
                R.string.doctor_cause_no_internet,
                R.string.doctor_next_network,
                node.code,
            )
        else -> node
    }
}

/** A clock offset as minutes, rounded away from zero, at least one. */
internal fun clockMinutes(seconds: Long): Long =
    ((kotlin.math.abs(seconds) + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1)

private fun clockLabel(seconds: Long) =
    (if (seconds < 0) "-" else "+") + clockMinutes(seconds) + "m"

/**
 * The line a support team reads first: the finding's code, the protocol, how many other nodes
 * worked, and the kind of network. No names, addresses or account details.
 */
internal fun doctorSupportCode(evidence: DoctorEvidence): String =
    doctorFinding(evidence).code.ifBlank { "—" }

/** How the answer reads at a glance: fine, worth a look, or broken. */
internal fun findingMark(finding: DoctorFinding): Mark =
    when (finding.cause) {
        R.string.doctor_cause_ok -> Mark.OK
        R.string.doctor_cause_some_sites,
        R.string.doctor_cause_restricted,
        R.string.doctor_cause_vpn_off,
        R.string.doctor_cause_incomplete,
        R.string.doctor_cause_no_node -> Mark.WARN
        else -> Mark.BAD
    }

/** "vless/ws", "hysteria2", "group": the protocol, with the transport where there is one. */
internal fun nodeProtocol(node: LocalNode?): String {
    node ?: return ""
    val transport =
        runCatching { org.json.JSONObject(node.config).optString("network") }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "tcp" }
    return listOfNotNull(node.type.lowercase(), transport).joinToString("/")
}
