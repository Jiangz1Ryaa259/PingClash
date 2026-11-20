// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.diagnostics.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The doctor's newer checks: plan, clock, phone, other nodes, DNS, region, support line. */
@RunWith(AndroidJUnit4::class)
class DoctorChecksTest {
    @get:Rule val locale = ResourceLocaleRule()

    private fun step(stage: Models.Stage, state: Models.State, code: Models.Code) =
        Models.Step(stage, state, code, 0, "", 0)

    private val failingTcp =
        listOf(
            step(Models.Stage.NETWORK, Models.State.PASSED, Models.Code.NETWORK_READY),
            step(Models.Stage.DNS, Models.State.PASSED, Models.Code.DNS_OK),
            step(Models.Stage.TCP, Models.State.FAILED, Models.Code.TCP_TIMEOUT),
        )

    private val phone =
        PhoneCheck(LinkKind.WIFI, true, false, "opportunistic", false, false, false, true, 34, "X")

    private fun evidence(
        plan: PlanCheck? = null,
        clock: ClockCheck? = null,
        phone: PhoneCheck? = this.phone,
        peers: List<PeerResult>? = null,
        dns: DnsCheck? = null,
    ) =
        DoctorEvidence(
            true,
            true,
            "rule",
            failingTcp,
            emptyList(),
            false,
            plan,
            clock,
            phone,
            peers,
            dns,
            "hysteria2",
            "Secret node name",
        )

    @Test
    fun thePlanAndTheClockComeBeforeTheNode() {
        val now = 2_000_000_000L
        val expired = doctorFinding(evidence(plan = PlanCheck(10, now - 60, now)))
        assertEquals(R.string.doctor_cause_expired, expired.cause)
        assertEquals("PLAN_EXPIRED", expired.code)
        val usedUp = doctorFinding(evidence(plan = PlanCheck(0, null, now)))
        assertEquals(R.string.doctor_cause_used_up, usedUp.cause)
        val clock = doctorFinding(evidence(clock = ClockCheck(-421, true)))
        assertEquals(R.string.doctor_cause_clock, clock.cause)
        assertEquals("8", clock.arg)
        assertEquals("CLOCK_-8m", clock.code)
        // Within the limit the clock says nothing.
        assertEquals(
            R.string.doctor_cause_tcp,
            doctorFinding(evidence(clock = ClockCheck(40, true))).cause,
        )
    }

    @Test
    fun nothingPastThePhoneRunsWhenNothingCouldConnect() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(evidence().blocked)
        assertTrue(evidence(phone = phone.copy(link = LinkKind.NONE)).blocked)
        assertTrue(evidence(phone = phone.copy(captive = true)).blocked)
        assertTrue(evidence(plan = PlanCheck(0, null, now)).blocked)
        assertTrue(evidence(plan = PlanCheck(null, now - 60, now)).blocked)
    }

    @Test
    fun thePhonesNetworkComesFirst() {
        assertEquals(
            R.string.doctor_cause_network,
            doctorFinding(evidence(phone = phone.copy(link = LinkKind.NONE))).cause,
        )
        assertEquals(
            R.string.doctor_cause_captive,
            doctorFinding(evidence(phone = phone.copy(captive = true))).cause,
        )
    }

    @Test
    fun theAnswerNamesOnlyThisNodesProblem() {
        val all =
            doctorFinding(
                evidence(peers = List(3) { PeerResult("n$it", false, 0, "proxy_timeout") })
            )
        assertEquals(R.string.doctor_cause_tcp, all.cause)
        assertEquals(R.string.doctor_next_node, all.next)
        val one =
            doctorFinding(
                evidence(
                    peers =
                        listOf(
                            PeerResult("Japan 02", true, 64, ""),
                            PeerResult("US 03", true, 172, ""),
                            PeerResult("SG 01", false, 0, "proxy_timeout"),
                        )
                )
            )
        // Working nodes elsewhere change neither the answer nor its suggestion.
        assertEquals(R.string.doctor_cause_tcp, one.cause)
        assertEquals(R.string.doctor_next_node, one.next)
        assertEquals(null, one.arg)
    }

    @Test
    fun aTamperedAnswerExplainsAnUnreachableServer() {
        val finding =
            doctorFinding(
                evidence(
                    dns = DnsCheck(DnsVerdict.TAMPERED, listOf("10.0.33.7"), listOf("203.0.113.25"))
                )
            )
        assertEquals(R.string.doctor_cause_dns_tampered, finding.cause)
        assertEquals(DnsVerdict.TAMPERED, compareDns(listOf("10.0.33.7"), listOf("203.0.113.25")))
        assertEquals(DnsVerdict.TAMPERED, compareDns(listOf("198.18.0.4"), listOf("203.0.113.25")))
        // A CDN may give different public addresses: not tampering.
        assertEquals(
            DnsVerdict.DIFFERENT,
            compareDns(listOf("203.0.113.9"), listOf("203.0.113.25")),
        )
        assertEquals(DnsVerdict.SAME, compareDns(listOf("203.0.113.25"), listOf("203.0.113.25")))
        assertEquals(DnsVerdict.UNKNOWN, compareDns(emptyList(), emptyList()))
        // No answer from the network may be a timeout: not tampering.
        assertEquals(DnsVerdict.UNKNOWN, compareDns(emptyList(), listOf("203.0.113.25")))
        assertTrue(reservedAddress("::"))
        assertTrue(reservedAddress("::1"))
        assertTrue(reservedAddress("fe80::1"))
        assertTrue(reservedAddress("fd00::7"))
        assertTrue(reservedAddress("ff02::1"))
        assertTrue(reservedAddress("2001:db8::1"))
        assertTrue(reservedAddress("::ffff:10.0.0.1"))
        assertFalse(reservedAddress("2606:4700:4700::1111"))
        assertFalse(reservedAddress("::ffff:8.8.8.8"))
        assertTrue(reservedAddress("172.20.1.1"))
        assertTrue(reservedAddress("100.100.1.1"))
        assertFalse(reservedAddress("172.32.1.1"))
        assertFalse(reservedAddress("8.8.8.8"))
    }

    @Test
    fun aLargeEncryptedDnsReplyIsCutOffAtOneMessage() {
        // A wrong URL answering with a large page is read only up to one DNS message and more.
        val page = java.io.ByteArrayInputStream(ByteArray(1_000_000))
        assertEquals(65_536, readAtMost(page, 65_536).size)
        assertEquals(3, readAtMost(java.io.ByteArrayInputStream(ByteArray(3)), 65_536).size)
    }

    @Test
    fun aDnsReplyIsReadBack() {
        val query = dnsQuery(0x1234, "node.example.com")
        // The query echoed with one A answer, as a server sends it.
        val reply =
            query.copyOf().also {
                it[2] = 0x81.toByte()
                it[3] = 0x80.toByte()
                it[7] = 1
            } +
                byteArrayOf(
                    0xc0.toByte(),
                    12,
                    0,
                    1,
                    0,
                    1,
                    0,
                    0,
                    0,
                    60,
                    0,
                    4,
                    203.toByte(),
                    0,
                    113,
                    25,
                )
        assertEquals(listOf("203.0.113.25"), dnsAnswers(reply, 0x1234))
        assertEquals(emptyList<String>(), dnsAnswers(reply, 0x9999))
    }

    @Test
    fun provincesAreFoundOnThePhone() {
        assertEquals(Province.GUANGDONG, nearestProvince(23.13, 113.26))
        assertEquals(Province.SICHUAN, nearestProvince(30.66, 104.06))
        assertEquals(Province.SHANGHAI, nearestProvince(31.23, 121.47))
        assertEquals(Province.BEIJING, nearestProvince(39.90, 116.40))
        assertEquals(null, nearestProvince(35.68, 139.69 + 10))
    }

    @Test
    fun theErrorCodeCarriesNoNamesOrAddresses() {
        val line =
            doctorSupportCode(
                evidence(
                    peers = listOf(PeerResult("Japan 02", false, 0, "x")),
                    dns = DnsCheck(DnsVerdict.SAME, listOf("203.0.113.25"), listOf("203.0.113.25")),
                )
            )
        assertEquals("TCP_TIMEOUT", line)
        assertFalse("Japan" in line || "Secret" in line || "203.0" in line)
    }
}
