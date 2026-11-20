package dev.pingclash.app

import dev.pingclash.diagnostics.Models

/** The node check the connection doctor runs: its steps, report and node. */
data class DiagnosticUiState(
    val running: Boolean = false,
    val steps: List<Models.Step> = emptyList(),
    val report: Models.Report? = null,
    val nodeId: String? = null,
)
