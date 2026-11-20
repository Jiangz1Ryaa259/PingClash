// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import dev.pingclash.diagnostics.Models

/** How a row of the doctor's result reads at a glance. */
internal enum class Mark {
    OK,
    WARN,
    BAD,
    SKIP,
}

/** One row of the result: a label, a mark and a short value; no explanation under it. */
internal data class DoctorLine(val label: Int, val mark: Mark, val value: String, val tag: String)

/** Every row, in the order the checks ran. */
internal fun doctorLines(evidence: DoctorEvidence, exitLine: String?): List<DoctorLine> =
    listOfNotNull(
        evidence.plan?.let(::planLine),
        evidence.clock?.let(::clockLine),
        evidence.phone?.let(::phoneLine),
        nodeLine(evidence.steps),
        peersLine(evidence),
        dnsLine(evidence),
        websitesLine(evidence),
        exitLine(evidence, exitLine),
    )

private fun planLine(plan: PlanCheck): DoctorLine {
    val (mark, value) =
        when {
            plan.expired -> Mark.BAD to tr(R.string.doctor_plan_expired)
            plan.usedUp -> Mark.BAD to tr(R.string.doctor_plan_used_up)
            !plan.known -> Mark.SKIP to tr(R.string.doctor_plan_unknown)
            else ->
                Mark.OK to
                    (plan.remaining?.let { tr(R.string.doctor_plan_left, formatBytes(it)) }
                        ?: tr(R.string.doctor_plan_active))
        }
    return DoctorLine(R.string.doctor_row_plan, mark, value, "plan")
}

private fun clockLine(clock: ClockCheck): DoctorLine {
    val skew = clock.skewSeconds
    val (mark, value) =
        when {
            skew != null && clock.off ->
                Mark.BAD to
                    tr(
                        if (skew < 0) R.string.doctor_clock_behind else R.string.doctor_clock_ahead,
                        clockMinutes(skew),
                    )
            skew != null -> Mark.OK to tr(R.string.doctor_clock_ok)
            clock.automatic -> Mark.OK to tr(R.string.doctor_clock_auto)
            else -> Mark.WARN to tr(R.string.doctor_clock_manual)
        }
    return DoctorLine(R.string.doctor_row_clock, mark, value, "clock")
}

/** The one thing most likely to get in the way, or the network type when nothing does. */
private fun phoneLine(phone: PhoneCheck): DoctorLine {
    val issue = phoneIssue(phone)
    val mark =
        when {
            phone.offline || issue?.second == true -> Mark.BAD
            issue != null -> Mark.WARN
            else -> Mark.OK
        }
    val value = issue?.let { tr(it.first) } ?: linkLabel(phone.link)
    return DoctorLine(R.string.doctor_row_phone, mark, value, "phone")
}

/** The phone's one problem as a short label, and whether it stops the connection outright. */
private fun phoneIssue(phone: PhoneCheck): Pair<Int, Boolean>? =
    when {
        phone.captive -> R.string.doctor_phone_short_captive to true
        !phone.offline && !phone.validated -> R.string.doctor_phone_short_unvalidated to true
        phone.privateDns == "hostname" -> R.string.doctor_phone_short_private_dns to false
        phone.batteryRestricted -> R.string.doctor_phone_short_battery to false
        phone.dataSaver -> R.string.doctor_phone_short_data_saver to false
        phone.systemProxy -> R.string.doctor_phone_short_proxy to false
        else -> null
    }

private fun nodeLine(steps: List<Models.Step>?): DoctorLine {
    val failed = steps?.firstOrNull { it.state == Models.State.FAILED }
    val proxy = steps?.lastOrNull { it.stage == Models.Stage.PROXY }
    val mark =
        when {
            failed != null -> Mark.BAD
            proxy?.state == Models.State.PASSED -> Mark.OK
            else -> Mark.SKIP
        }
    return DoctorLine(R.string.doctor_row_node, mark, doctorNodeLine(steps), "node")
}

private fun peersLine(evidence: DoctorEvidence): DoctorLine {
    val peers = evidence.peers
    val working = peers.orEmpty().count { it.ok }
    val (mark, value) =
        when {
            peers == null -> Mark.SKIP to tr(R.string.doctor_peers_not_needed)
            peers.isEmpty() -> Mark.SKIP to tr(R.string.doctor_peers_none)
            working == 0 -> Mark.BAD to tr(R.string.doctor_peers_value, working, peers.size)
            working == peers.size -> Mark.OK to tr(R.string.doctor_peers_value, working, peers.size)
            else -> Mark.WARN to tr(R.string.doctor_peers_value, working, peers.size)
        }
    return DoctorLine(R.string.doctor_row_peers, mark, value, "peers")
}

private fun dnsLine(evidence: DoctorEvidence): DoctorLine {
    val (mark, value) =
        when (evidence.dns?.verdict) {
            DnsVerdict.SAME -> Mark.OK to R.string.doctor_dns_same
            DnsVerdict.DIFFERENT -> Mark.OK to R.string.doctor_dns_different
            DnsVerdict.TAMPERED -> Mark.BAD to R.string.doctor_dns_tampered
            DnsVerdict.LITERAL -> Mark.SKIP to R.string.doctor_dns_literal
            DnsVerdict.UNKNOWN,
            null -> Mark.SKIP to R.string.doctor_not_checked
        }
    return DoctorLine(R.string.doctor_row_dns, mark, tr(value), "dns")
}

private fun websitesLine(evidence: DoctorEvidence): DoctorLine {
    val sites = tallyWebsites(evidence.websites)
    val mark =
        when {
            sites.checked == 0 -> Mark.SKIP
            sites.reachable == sites.checked -> Mark.OK
            sites.reachable == 0 -> Mark.BAD
            else -> Mark.WARN
        }
    return DoctorLine(
        R.string.doctor_row_websites,
        mark,
        doctorWebsitesLine(evidence.websites),
        "websites",
    )
}

private fun exitLine(evidence: DoctorEvidence, exitLine: String?): DoctorLine =
    DoctorLine(
        R.string.doctor_row_ip,
        if (evidence.vpnActive && exitLine != null) Mark.OK else Mark.SKIP,
        when {
            !evidence.vpnActive -> tr(R.string.doctor_vpn_off)
            else -> exitLine ?: tr(R.string.doctor_not_checked)
        },
        "ip",
    )
