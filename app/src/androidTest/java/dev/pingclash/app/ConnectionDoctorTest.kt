// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.diagnostics.Models
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The doctor names the most basic thing that failed, and what to do about it. */
@RunWith(AndroidJUnit4::class)
class ConnectionDoctorTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun step(stage: Models.Stage, state: Models.State, code: Models.Code, ms: Long = 0) =
        Models.Step(stage, state, code, ms, "", 0)

    private val passing =
        listOf(
            step(Models.Stage.NETWORK, Models.State.PASSED, Models.Code.NETWORK_READY),
            step(Models.Stage.DNS, Models.State.PASSED, Models.Code.DNS_OK),
            step(Models.Stage.TCP, Models.State.PASSED, Models.Code.TCP_OK),
            step(Models.Stage.TLS, Models.State.PASSED, Models.Code.TLS_OK),
            step(Models.Stage.PROXY, Models.State.PASSED, Models.Code.PROXY_OK, 180),
        )

    private fun failingAt(stage: Models.Stage, code: Models.Code) =
        passing.map { if (it.stage == stage) step(stage, Models.State.FAILED, code) else it }

    private fun evidence(
        steps: List<Models.Step>? = passing,
        vpn: Boolean = true,
        mode: String = "rule",
        sites: List<Latency?> = List(4) { Latency.Measured(120) },
        selected: Boolean = true,
    ) = DoctorEvidence(selected, vpn, mode, steps, sites, exitChecked = vpn)

    // Cause and next step only: the support code and the named node are checked in
    // DoctorChecksTest.
    private fun finding(cause: Int, next: Int?) = cause to next

    private fun doctorFinding(evidence: DoctorEvidence) =
        dev.pingclash.app.doctorFinding(evidence).let { it.cause to it.next }

    @Test
    fun theFirstFailingStageIsTheConclusion() {
        assertEquals(
            finding(R.string.doctor_cause_no_node, R.string.doctor_next_choose),
            doctorFinding(evidence(selected = false, steps = null)),
        )
        assertEquals(
            finding(R.string.doctor_cause_incomplete, R.string.doctor_next_again),
            doctorFinding(evidence(steps = null)),
        )
        assertEquals(
            finding(R.string.doctor_cause_dns, R.string.doctor_next_dns),
            doctorFinding(evidence(failingAt(Models.Stage.DNS, Models.Code.DNS_FAILED))),
        )
        assertEquals(
            finding(R.string.doctor_cause_tcp, R.string.doctor_next_node),
            doctorFinding(evidence(failingAt(Models.Stage.TCP, Models.Code.TCP_TIMEOUT))),
        )
        assertEquals(
            finding(R.string.doctor_cause_tls, R.string.doctor_next_tls),
            doctorFinding(evidence(failingAt(Models.Stage.TLS, Models.Code.TLS_CERTIFICATE))),
        )
        assertEquals(
            finding(R.string.doctor_cause_proxy, R.string.doctor_next_update),
            doctorFinding(evidence(failingAt(Models.Stage.PROXY, Models.Code.PROXY_FAILED))),
        )
        // A failing node outranks what the websites show.
        assertEquals(
            finding(R.string.doctor_cause_tcp, R.string.doctor_next_node),
            doctorFinding(
                evidence(failingAt(Models.Stage.TCP, Models.Code.TCP_REFUSED), sites = emptyList())
            ),
        )
    }

    @Test
    fun aWorkingNodeIsJudgedByTheVpnAndTheWebsites() {
        val failed = Latency.Failed(WebsiteFailure.TIMEOUT)
        val restricted = Latency.Failed(WebsiteFailure.RESTRICTED)
        assertEquals(
            finding(R.string.doctor_cause_vpn_off, R.string.doctor_next_connect),
            doctorFinding(evidence(vpn = false)),
        )
        assertEquals(
            finding(R.string.doctor_cause_sites_direct, R.string.doctor_next_mode),
            doctorFinding(evidence(mode = "direct", sites = List(4) { failed })),
        )
        assertEquals(
            finding(R.string.doctor_cause_sites, R.string.doctor_next_rules),
            doctorFinding(evidence(sites = List(4) { failed })),
        )
        assertEquals(
            finding(R.string.doctor_cause_restricted, R.string.doctor_next_region),
            doctorFinding(evidence(sites = listOf(Latency.Measured(90), restricted, null, null))),
        )
        assertEquals(
            finding(R.string.doctor_cause_some_sites, R.string.doctor_next_connections),
            doctorFinding(evidence(sites = listOf(Latency.Measured(90), failed))),
        )
        assertEquals(finding(R.string.doctor_cause_ok, null), doctorFinding(evidence()))
        assertEquals(
            tr(R.string.doctor_websites_line, 1, 2),
            doctorWebsitesLine(listOf(Latency.Measured(90), failed, null)),
        )
        assertEquals(tr(R.string.doctor_node_ok, 180L), doctorNodeLine(passing))
    }

    @Test
    fun aLiveRunBlamesANodeWhoseServerRefusesConnections() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        // A port nothing listens on: the node's server refuses the connection.
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val node =
            org.json
                .JSONObject()
                .put("name", "Closed")
                .put("type", "socks5")
                .put("server", "127.0.0.1")
                .put("port", closed)
        val raw = org.json.JSONObject().put("proxies", org.json.JSONArray().put(node)).toString()
        val profile = repo.importProfile("Doctor fixture", raw)
        try {
            repo.activate(profile.id)
            compose.waitUntil(5000) { repo.data.value.selected != null }
            compose.runOnUiThread { model.doctor.start() }
            compose.waitUntil(60_000) {
                model.doctor.state.value.let { !it.running && it.evidence != null }
            }
            val evidence = requireNotNull(model.doctor.state.value.evidence)
            assertEquals(
                finding(R.string.doctor_cause_tcp, R.string.doctor_next_node),
                doctorFinding(evidence),
            )
            // The VPN is off, so the IP was not checked.
            assertEquals(false, evidence.vpnActive)
        } finally {
            model.doctor.cancel()
            repo.delete(profile.id)
        }
    }

    @Test
    fun theCardShowsTheConclusionAndTheNextStep() {
        val state = DoctorState(evidence = evidence(vpn = false))
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) { ConnectionDoctorTile(state, true, {}, {}) }
            }
        }
        compose.onNodeWithTag("doctor-cause").assertExists()
        compose.onNodeWithText(tr(R.string.doctor_cause_vpn_off)).assertExists()
        compose.onNodeWithText(tr(R.string.doctor_next_connect)).assertExists()
        compose.onNodeWithText(tr(R.string.doctor_vpn_off)).assertExists()
    }
}
