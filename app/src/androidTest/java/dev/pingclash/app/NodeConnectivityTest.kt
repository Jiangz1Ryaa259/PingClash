package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.diagnostics.Models
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeConnectivityTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()

    @Test
    fun certificatePolicyNeverBecomesConnectivityEvidenceOrLeaksAcrossNodes() {
        val policy =
            Models.Step(
                Models.Stage.POLICY,
                Models.State.SKIPPED,
                Models.Code.NODE_CERTIFICATE_UNVERIFIED,
                0,
                "",
                0,
            )
        val success =
            Models.Step(
                Models.Stage.PROXY,
                Models.State.PASSED,
                Models.Code.PROXY_OK,
                37,
                "",
                0,
                204,
            )
        fun state(vararg steps: Models.Step) =
            DiagnosticUiState(
                nodeId = "node-a",
                report = Models.Report("fixture", "fixture", "未知", steps.toList(), 37),
            )
        assertEquals("连通性：尚未测试", nodeConnectivitySummary(DiagnosticUiState(), "node-a"))
        assertEquals("连通性：本次测试未完成", nodeConnectivitySummary(state(policy), "node-a"))
        assertEquals(
            "连通性：本次访问成功 · 37 ms",
            nodeConnectivitySummary(state(policy, success), "node-a"),
        )
        assertEquals("连通性：尚未测试", nodeConnectivitySummary(state(policy, success), "node-b"))
        assertEquals("连通性：尚未测试", nodeConnectivitySummary(state(policy, success), null))
        assertEquals(
            "连通性：测试中…",
            nodeConnectivitySummary(DiagnosticUiState(running = true, nodeId = "node-a"), "node-a"),
        )
        assertEquals(
            "连通性：本次访问未成功",
            nodeConnectivitySummary(
                state(
                    policy,
                    Models.Step(
                        Models.Stage.PROXY,
                        Models.State.FAILED,
                        Models.Code.PROXY_TIMEOUT,
                        37,
                        "",
                        0,
                    ),
                ),
                "node-a",
            ),
        )
        for (terminal in listOf(Models.State.CANCELLED, Models.State.BLOCKED, Models.State.STALE)) {
            val summary =
                nodeConnectivitySummary(
                    state(
                        policy,
                        success,
                        Models.Step(
                            Models.Stage.PROXY,
                            terminal,
                            Models.Code.CONFIG_CHANGED,
                            0,
                            "",
                            0,
                        ),
                    ),
                    "node-a",
                )
            assertFalse("Old success survived terminal state: $terminal", summary.contains("访问成功"))
        }
    }
}
