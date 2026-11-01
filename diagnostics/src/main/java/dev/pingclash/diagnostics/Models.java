package dev.pingclash.diagnostics;

import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Models {
    private Models() {}

    public enum Stage {
        NETWORK("diagnostic_db3e6453db"), DNS("diagnostic_7a6232872d"), TCP("diagnostic_e21bd916a5"),
        TLS("diagnostic_a990a60997"), POLICY("diagnostic_b9c1e1d0e6"), ROUTE("diagnostic_dd2e781707"), PROXY("diagnostic_1f23c2c551");
        private final String key;
        public String title() { return DiagnosticText.text(key); }
        Stage(String title) { this.key = title; }
    }

    public enum State {
        PASSED("diagnostic_1e9f2561b7"), FAILED("diagnostic_28384d7afd"), SKIPPED("diagnostic_fc50a99caa"), UNKNOWN("diagnostic_4d8c1c5b42"),
        CANCELLED("diagnostic_a37778f17c"), STALE("diagnostic_2fe5a8d0ee"), BLOCKED("diagnostic_878363589f");
        private final String key;
        public String title() { return DiagnosticText.text(key); }
        State(String title) { this.key = title; }
    }

    public enum Family { AUTO, IPV4, IPV6 }

    // Messages are a whitelist. Raw exceptions, hosts, SNI and credentials never enter a report.
    public enum Code {
        GROUP_ROUTE("diagnostic_de96e215ad"),
        NETWORK_READY("diagnostic_1220f0afb1"),
        NETWORK_UNAVAILABLE("diagnostic_e119133695"),
        VPN_ACTIVE("diagnostic_497ea7c8df"),
        NETWORK_CHANGED("diagnostic_2c6141c9d1"),
        CONFIG_CHANGED("diagnostic_a06e2f7464"),
        DNS_OK("diagnostic_5e6306beb8"),
        LITERAL_ADDRESS("diagnostic_1fa2095106"),
        DNS_FAILED("diagnostic_4f6e6aef34"),
        DNS_TIMEOUT("diagnostic_8323aed76c"),
        NO_MATCHING_ADDRESS("diagnostic_73889f1c38"),
        TCP_OK("diagnostic_324a2fb408"),
        TCP_REFUSED("diagnostic_1040fe9199"),
        TCP_TIMEOUT("diagnostic_8cee8a6c08"),
        TCP_FAILED("diagnostic_2f4a98946a"),
        TLS_OK("diagnostic_845962210f"),
        TLS_CERTIFICATE("diagnostic_9c4ff8cd30"),
        TLS_TIMEOUT("diagnostic_85697593c5"),
        TLS_FAILED("diagnostic_f436822f06"),
        TLS_DISABLED("diagnostic_479efd4122"),
        DEPENDENCY_FAILED("diagnostic_724f043f2c"),
        CORE_UNAVAILABLE("diagnostic_9a6458e7eb"),
        PROXY_NOT_REQUESTED("diagnostic_3efc8c490f"),
        NODE_CERTIFICATE_UNVERIFIED("diagnostic_3daf983439"),
        PROXY_CHAIN_ACTIVE("diagnostic_0064f169a7"),
        PROXY_OK("diagnostic_176db0ac7b"),
        PROXY_HTTP_STATUS("diagnostic_5e1e2dc3fe"),
        PROXY_TIMEOUT("diagnostic_61898abe66"),
        PROXY_FAILED("diagnostic_58dd8e81ef"),
        PROXY_CERTIFICATE("diagnostic_e642c54e2f"),
        TARGET_CERTIFICATE("diagnostic_ee5360d995"),
        PROXY_QUIC_TIMEOUT("diagnostic_966595c730"),
        PROXY_QUIC_TLS("diagnostic_c0fe650e0f"),
        PROXY_DNS("diagnostic_7b07c45933"),
        PROXY_CHANNEL_TIMEOUT("diagnostic_42a4997f82"),
        PROXY_CHANNEL_FAILED("diagnostic_63b64999b6"),
        PROXY_CONFIG_INVALID("diagnostic_ea04adcef3"),
        CORE_BUSY("diagnostic_da75645a01"),
        DNS_ONLY("diagnostic_83fb0b4014"),
        UDP_PROTOCOL("diagnostic_bf1ec6ae9c"),
        CANCELLED("diagnostic_a3adba9eaa"),
        DEADLINE("diagnostic_03c5bcd8f1"),
        LOCAL_RESTRICTION("diagnostic_c2fe14386d"),
        INTERNAL_ERROR("diagnostic_7f9c427e0c");

        private final String key;
        public String message() { return DiagnosticText.text(key); }
        Code(String message) { this.key = message; }
    }

    public static final class Request {
        public final String host;
        public final int port;
        public final boolean useTls;
        public final String serverName;
        public final Family family;
        public final boolean dnsOnly;

        public Request(String host, int port, boolean useTls, String serverName, Family family) {
            this(host, port, useTls, serverName, family, false);
        }
        public Request(String host, int port, boolean useTls, String serverName, Family family, boolean dnsOnly) {
            this.host = normalizeHost(host);
            if (port < 1 || port > 65535) throw new IllegalArgumentException(DiagnosticText.text("diagnostic_7b13119502"));
            this.port = port;
            this.useTls = useTls;
            this.serverName = serverName == null || serverName.trim().isEmpty()
                ? this.host : normalizeHost(serverName);
            this.family = family == null ? Family.AUTO : family;
            this.dnsOnly = dnsOnly;
        }
    }

    public static String normalizeHost(String raw) {
        if (raw == null) throw new IllegalArgumentException(DiagnosticText.text("diagnostic_7c4a238bab"));
        String host = raw.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || host.matches(".*[\\s/@?#%\\\\].*"))
            throw new IllegalArgumentException(DiagnosticText.text("diagnostic_624c9062b5"));
        if (host.contains(":")) {
            try {
                if (InetAddress.getByName(host).getAddress().length != 16) throw new Exception();
                return host;
            } catch (Exception e) {
                throw new IllegalArgumentException(DiagnosticText.text("diagnostic_0552629e2e"));
            }
        }
        if (host.matches("[0-9.]+")) {
            String[] pieces = host.split("\\.", -1);
            if (pieces.length != 4) throw new IllegalArgumentException(DiagnosticText.text("diagnostic_0525c75b38"));
            for (String piece : pieces) {
                if (piece.isEmpty() || piece.length() > 3 || Integer.parseInt(piece) > 255)
                    throw new IllegalArgumentException(DiagnosticText.text("diagnostic_0525c75b38"));
            }
            return host;
        }
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            String withoutDot = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
            if (withoutDot.isEmpty() || withoutDot.length() > 253) throw new Exception();
            for (String label : withoutDot.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63) throw new Exception();
            }
            return withoutDot;
        } catch (Exception e) {
            throw new IllegalArgumentException(DiagnosticText.text("diagnostic_83dc48d1e6"));
        }
    }

    public static boolean isLiteral(String host) {
        return host.contains(":") || host.matches("[0-9.]+");
    }

    public static final class Step {
        public final Stage stage;
        public final State state;
        public final Code code;
        public final long elapsedMs;
        public final String addressFamily;
        public final int attempt;
        public final int httpStatus;

        public Step(Stage stage, State state, Code code, long elapsedMs, String addressFamily, int attempt) {
            this(stage, state, code, elapsedMs, addressFamily, attempt, 0);
        }
        public Step(Stage stage, State state, Code code, long elapsedMs, String addressFamily, int attempt, int httpStatus) {
            this.stage = stage;
            this.state = state;
            this.code = code;
            this.elapsedMs = Math.max(0, elapsedMs);
            this.addressFamily = addressFamily;
            this.attempt = attempt;
            this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
        }
    }

    public static final class Report {
        public final String runId;
        public final String timestamp;
        public final String networkType;
        public final List<Step> steps;
        public final long elapsedMs;

        public Report(String runId, String timestamp, String networkType, List<Step> steps, long elapsedMs) {
            this.runId = runId;
            this.timestamp = timestamp;
            this.networkType = networkType;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.elapsedMs = elapsedMs;
        }

        public String conclusion() {
            for (Step step : steps) {
                if (step.state == State.STALE) return step.code.message();
            }
            for (Step step : steps) if (step.state == State.CANCELLED) return step.code.message();
            for (Step step : steps) {
                if (step.state == State.BLOCKED) return step.code.message();
            }
            for (Step step : steps) {
                if (step.stage == Stage.PROXY && step.state == State.PASSED) {
                    boolean auxiliaryFailure = false;
                    for (Step auxiliary : steps) if (auxiliary.stage != Stage.PROXY && auxiliary.state == State.FAILED) auxiliaryFailure = true;
                    String result = auxiliaryFailure ? DiagnosticText.text("diagnostic_5f5dff1787") : Code.PROXY_OK.message();
                    return result;
                }
            }
            for (Step step : steps) if (step.stage == Stage.PROXY && step.code == Code.PROXY_HTTP_STATUS) {
                return DiagnosticText.text("diagnostic_f90ceb9a5b") + (step.httpStatus > 0 ? step.httpStatus : DiagnosticText.text("diagnostic_ec0d9bdb00")) + DiagnosticText.text("diagnostic_664b29953e");
            }
            for (Step step : steps) if (step.stage == Stage.PROXY && step.state == State.FAILED &&
                (step.code == Code.PROXY_CERTIFICATE || step.code == Code.TARGET_CERTIFICATE ||
                 step.code == Code.PROXY_QUIC_TIMEOUT || step.code == Code.PROXY_QUIC_TLS ||
                 step.code == Code.PROXY_DNS || step.code == Code.PROXY_CHANNEL_TIMEOUT || step.code == Code.PROXY_CHANNEL_FAILED)) return step.code.message();
            // A failed candidate address must not hide a later successful TCP attempt.
            boolean tcpPassed = false;
            for (Step step : steps) if (step.stage == Stage.TCP && step.state == State.PASSED) tcpPassed = true;
            boolean tlsPassed = false;
            boolean auxiliaryFailure = false;
            Step proxyFailure = null;
            for (Step step : steps) {
                if (step.stage == Stage.TLS && step.state == State.PASSED) tlsPassed = true;
                if (step.state == State.FAILED && step.stage != Stage.PROXY && !(step.stage == Stage.TCP && tcpPassed)) auxiliaryFailure = true;
                if (step.stage == Stage.PROXY && (step.code == Code.PROXY_FAILED || step.code == Code.PROXY_TIMEOUT)) proxyFailure = step;
            }
            if (tcpPassed && !auxiliaryFailure && proxyFailure != null) {
                return (tlsPassed ? DiagnosticText.text("diagnostic_02615e55b8") : DiagnosticText.text("diagnostic_2e41f07e5b")) +
                    (proxyFailure.code == Code.PROXY_TIMEOUT ? DiagnosticText.text("diagnostic_5db526b479") : DiagnosticText.text("diagnostic_82ad278750")) +
                    DiagnosticText.text("diagnostic_1a914177ee");
            }
            for (Step step : steps) {
                if (step.state == State.FAILED && !(step.stage == Stage.TCP && tcpPassed)) return step.code.message();
            }
            for (Step step : steps) if (step.code == Code.DNS_ONLY) return DiagnosticText.text("diagnostic_dd748306b8");
            return DiagnosticText.text("diagnostic_9ef0a6878b");
        }
    }
}

