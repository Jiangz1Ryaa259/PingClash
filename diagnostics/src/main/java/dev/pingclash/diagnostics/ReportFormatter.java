package dev.pingclash.diagnostics;

import dev.pingclash.diagnostics.Models.Report;
import dev.pingclash.diagnostics.Models.Step;

public final class ReportFormatter {
    private ReportFormatter() {}

    public static String markdown(Report report) {
        StringBuilder out = new StringBuilder(DiagnosticText.text("diagnostic_451d843199"));
        boolean proxyRequested = false;
        for (Step step : report.steps) if (step.stage == dev.pingclash.diagnostics.Models.Stage.PROXY &&
            step.code != dev.pingclash.diagnostics.Models.Code.CORE_UNAVAILABLE &&
            step.code != dev.pingclash.diagnostics.Models.Code.PROXY_NOT_REQUESTED &&
            step.code != dev.pingclash.diagnostics.Models.Code.DNS_ONLY &&
            step.code != dev.pingclash.diagnostics.Models.Code.DEPENDENCY_FAILED) proxyRequested = true;
        out.append(DiagnosticText.format("diagnostic_24c5b0bf01", DiagnosticText.version())).append(safeRunId(report.runId))
            .append(DiagnosticText.text("diagnostic_65082036ca")).append(safeTimestamp(report.timestamp))
            .append(DiagnosticText.text("diagnostic_a158f35474"))
            .append(DiagnosticText.text("diagnostic_60c12356ce")).append(safeNetwork(report.networkType))
            .append(DiagnosticText.text("diagnostic_29085f35dc")).append(proxyRequested ? DiagnosticText.text("diagnostic_f7ca29aa5d") : DiagnosticText.text("diagnostic_b089c998f6"))
            .append(DiagnosticText.text("diagnostic_959ad8dc6c")).append(report.elapsedMs).append(" ms\n\n")
            .append(report.conclusion()).append("\n\n")
            .append(DiagnosticText.text("diagnostic_1e6240243f"))
            .append("| --- | --- | --- | --- | --- |\n");
        for (Step step : report.steps) {
            String family = "IPv4".equals(step.addressFamily) || "IPv6".equals(step.addressFamily)
                ? step.addressFamily : "—";
            out.append("| ").append(step.stage.title()).append(" | ").append(step.state.title())
                .append(" | ").append(family).append(" / ").append(step.attempt)
                .append(" | ").append(step.elapsedMs).append(" ms | ").append(step.code.message());
            if (step.httpStatus > 0) out.append(" HTTP ").append(step.httpStatus).append("。");
            out.append(" |\n");
        }
        return out.append(DiagnosticText.text("diagnostic_91fe2b7809"))
            .append(DiagnosticText.text("diagnostic_42466d8f4a")).toString();
    }

    private static String safeNetwork(String value) {
        if ("Wi-Fi".equals(value) || DiagnosticText.text("diagnostic_2d05ff08f5").equals(value) || DiagnosticText.text("diagnostic_efe16032c9").equals(value) || DiagnosticText.text("diagnostic_45ff8362fe").equals(value)) return value;
        return DiagnosticText.text("diagnostic_4d8c1c5b42");
    }

    private static String safeRunId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? value : "—";
    }

    private static String safeTimestamp(String value) {
        if (value == null || value.length() > 40) return "—";
        try { return java.time.Instant.parse(value).toString(); }
        catch (java.time.format.DateTimeParseException invalid) { return "—"; }
    }
}
