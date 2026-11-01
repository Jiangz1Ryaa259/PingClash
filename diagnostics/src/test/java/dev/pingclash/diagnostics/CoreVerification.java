package dev.pingclash.diagnostics;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import dev.pingclash.diagnostics.Models.*;

/** Standalone verification: real loopback TCP/TLS plus deterministic failure injection. */
public final class CoreVerification {
    private static int passed;
    private static final InetAddress LOOPBACK;
    static {
        try { LOOPBACK = InetAddress.getByAddress(new byte[] {127, 0, 0, 1}); }
        catch (Exception impossible) { throw new ExceptionInInitializerError(impossible); }
    }

    interface Check { void run() throws Exception; }

    private static void installResources(String path) throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var elements=factory.newDocumentBuilder().parse(new java.io.File(path)).getElementsByTagName("string");
        var values=new java.util.HashMap<String,String>();
        for(int i=0;i<elements.getLength();i++) {
            var item=(org.w3c.dom.Element)elements.item(i);
            String value=item.getTextContent();
            if(value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);
            value=value.replace("\\n","\n").replace("\\'","'").replace("\\\"","\"").replace("\\\\","\\");
            values.put(item.getAttribute("name"),value);
        }
        DiagnosticText.install(key -> java.util.Objects.requireNonNull(values.get(key),"Missing diagnostic resource: "+key));
    }

    public static void main(String[] args) throws Exception {
        installResources(args[1]);
        check("canary credentials cannot escape diagnostic metadata, errors or exports", () -> {
            String canary="CANARY-SUB-TOKEN-1234";
            String password="CANARY-PASSWORD-1234";
            String uuid="CANARY-UUID-1234";
            Binding binding=new Binding() {
                @Override public List<InetAddress> resolve(String h,int t,RunControl c) throws Exception {
                    throw new UnknownHostException("https://example.invalid/?token="+canary+" password="+password+" uuid="+uuid);
                }
            };
            Report normal=run(request("private.invalid",443,true,""),binding);
            Report hostile=new Report(canary,password,uuid,Arrays.asList(
                new Step(Stage.DNS,State.FAILED,Code.DNS_FAILED,0,canary,1)),0);
            for(Report report:Arrays.asList(normal,hostile)) {
                String output=ReportFormatter.markdown(report)+report.conclusion();
                require(!output.contains("CANARY-"),"canary escaped diagnostic output");
            }
        });
        check("reject subscription URL and credentials", () -> {
            rejects(() -> request("https://secret.invalid/?token=secret", 443, false, ""));
            rejects(() -> request("password@server.invalid", 443, false, ""));
        });
        check("validate host, address and port", () -> {
            rejects(() -> request("1.2.3.999", 443, false, ""));
            rejects(() -> request("bad..invalid", 443, false, ""));
            rejects(() -> request("server.invalid", 0, false, ""));
            rejects(() -> request("server.invalid", 65536, false, ""));
            require(Models.normalizeHost("[::1]").equals("::1"), "IPv6 brackets");
            require(Models.normalizeHost("例子.测试").startsWith("xn--"), "IDN conversion");
            require(Models.normalizeHost("server.invalid.").equals("server.invalid"), "trailing DNS dot broke SNI");
        });
        check("network unavailable does not implicate a node", () -> {
            Report report = new DiagnosticEngine().run(request("private.invalid", 443, true, ""),
                () -> { throw new ProbeFailure(State.BLOCKED, Code.NETWORK_UNAVAILABLE); },
                new RunControl(2000), steps -> {});
            has(report, Stage.NETWORK, State.BLOCKED, Code.NETWORK_UNAVAILABLE);
            has(report, Stage.TCP, State.SKIPPED, Code.DEPENDENCY_FAILED);
            unknownCore(report);
        });
        check("DNS failure skips TCP and TLS", () -> {
            Binding binding = new Binding() {
                @Override public List<InetAddress> resolve(String h, int t, RunControl c) throws Exception {
                    throw new UnknownHostException("secret-token-do-not-export");
                }
            };
            Report report = run(request("private.invalid", 443, true, ""), binding);
            has(report, Stage.DNS, State.FAILED, Code.DNS_FAILED);
            require(binding.sockets.get() == 0, "must not dial after DNS failure");
            require(!ReportFormatter.markdown(report).contains("secret-token"), "raw exception leaked");
            unknownCore(report);
        });
        check("DNS timeout is distinct", () -> {
            Binding binding = new Binding() {
                @Override public List<InetAddress> resolve(String h, int t, RunControl c) throws Exception {
                    throw new TimeoutException();
                }
            };
            has(run(request("private.invalid", 443, true, ""), binding), Stage.DNS, State.FAILED, Code.DNS_TIMEOUT);
        });
        check("address family mismatch never dials", () -> {
            Binding binding = new Binding();
            Request req = new Request("private.invalid", 443, false, "", Family.IPV6);
            has(run(req, binding), Stage.DNS, State.FAILED, Code.NO_MATCHING_ADDRESS);
            require(binding.sockets.get() == 0, "IPv4 result used for IPv6 request");
        });
        check("real loopback TCP and literal DNS skip", () -> {
            try (Peer peer = Peer.tcp(false)) {
                Binding binding = new Binding();
                Report report = run(request("127.0.0.1", peer.port(), false, ""), binding);
                has(report, Stage.DNS, State.SKIPPED, Code.LITERAL_ADDRESS);
                has(report, Stage.TCP, State.PASSED, Code.TCP_OK);
                has(report, Stage.TLS, State.SKIPPED, Code.TLS_DISABLED);
                require(binding.resolutions.get() == 0, "literal triggered DNS");
                require(binding.closed, "binding not closed");
                unknownCore(report);
            }
        });
        check("real loopback refused port", () -> {
            int port;
            try (ServerSocket reserved = new ServerSocket(0, 1, LOOPBACK)) { port = reserved.getLocalPort(); }
            has(run(request("private.invalid", port, true, ""), new Binding()),
                Stage.TCP, State.FAILED, Code.TCP_REFUSED);
        });
        check("unreachable ConnectException is not reported as refused", () -> {
            Binding binding = new Binding() {
                @Override public Socket createSocket() {
                    return new Socket() {
                        @Override public void connect(java.net.SocketAddress address, int timeout) throws IOException {
                            throw new java.net.ConnectException("Network is unreachable");
                        }
                    };
                }
            };
            has(run(request("private.invalid", 443, false, ""), binding), Stage.TCP, State.FAILED, Code.TCP_FAILED);
        });
        check("failed candidate followed by success is not an overall failure", () -> {
            try (Peer peer = Peer.tcp(false)) {
                Binding binding = new Binding();
                binding.addresses = Arrays.asList(InetAddress.getByAddress(new byte[] {127, 0, 0, 2}), LOOPBACK);
                Report report = run(request("private.invalid", peer.port(), false, ""), binding);
                has(report, Stage.TCP, State.FAILED, Code.TCP_REFUSED);
                has(report, Stage.TCP, State.PASSED, Code.TCP_OK);
                require(report.conclusion().startsWith("辅助探测已完成"), "candidate failure hid success");
            }
        });
        check("network change invalidates the run", () -> {
            Binding binding = new Binding() {
                int checks;
                @Override public boolean isCurrent() { return ++checks < 2; }
            };
            Report report = run(request("private.invalid", 443, true, ""), binding);
            has(report, Stage.DNS, State.STALE, Code.NETWORK_CHANGED);
            require(binding.sockets.get() == 0, "dialed after network changed");
        });
        check("automatic selection interleaves IPv6 and IPv4", () -> {
            byte[] first = new byte[16]; first[15] = 1;
            byte[] second = new byte[16]; second[15] = 2;
            List<InetAddress> order = new java.util.ArrayList<>();
            Binding binding = new Binding() {
                @Override public Socket createSocket() {
                    return new Socket() {
                        @Override public void connect(java.net.SocketAddress target, int timeout) throws IOException {
                            InetAddress address = ((InetSocketAddress) target).getAddress();
                            order.add(address);
                            if (address instanceof java.net.Inet6Address) throw new java.net.SocketTimeoutException();
                        }
                    };
                }
            };
            binding.addresses = Arrays.asList(InetAddress.getByAddress(first), InetAddress.getByAddress(second), LOOPBACK);
            Report report = run(request("private.invalid", 443, false, ""), binding);
            require(order.size() == 2 && order.get(1).equals(LOOPBACK), "IPv4 candidate starved behind IPv6 addresses");
            has(report, Stage.TCP, State.PASSED, Code.TCP_OK);
        });
        check("cancellation before start skips work", () -> {
            RunControl control = new RunControl(2000);
            control.cancel();
            Binding binding = new Binding();
            Report report = new DiagnosticEngine().run(request("private.invalid", 443, true, ""),
                () -> binding, control, steps -> {});
            has(report, Stage.NETWORK, State.CANCELLED, Code.CANCELLED);
            require(binding.sockets.get() == 0 && binding.resolutions.get() == 0, "cancelled run performed work");
        });
        check("cancel closes a real socket during stalled TLS", () -> {
            try (Peer peer = Peer.tcp(true)) {
                RunControl control = new RunControl(5000);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<Report> result = executor.submit(() -> new DiagnosticEngine().run(
                        request("private.invalid", peer.port(), true, "localhost"),
                        Binding::new, control, steps -> {}));
                    require(peer.accepted.await(2, TimeUnit.SECONDS), "no loopback connection");
                    control.cancel();
                    Report report = result.get(2, TimeUnit.SECONDS);
                    require(report.steps.stream().anyMatch(s -> s.state == State.CANCELLED), "cancel became network error");
                } finally { executor.shutdownNow(); }
            }
        });
        check("hard total deadline interrupts stalled TLS", () -> {
            try (Peer peer = Peer.tcp(true)) {
                long start = System.nanoTime();
                Report report = new DiagnosticEngine().run(request("private.invalid", peer.port(), true, "localhost"),
                    Binding::new, new RunControl(250), steps -> {});
                require(report.steps.stream().anyMatch(s -> s.code == Code.DEADLINE), "deadline not reported");
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000, "deadline did not bound work");
            }
        });
        check("TLS step timeout is not a certificate error", () -> {
            try (Peer peer = Peer.tcp(true); RunControl control = new RunControl(3000)) {
                SocketProbe probe = new SocketProbe();
                Socket socket = probe.connect(new Binding(), LOOPBACK, peer.port(), 500, control);
                try {
                    probe.handshake(socket, request("private.invalid", peer.port(), true, "localhost"), 100, control);
                    throw new AssertionError("stalled TLS succeeded");
                } catch (ProbeFailure expected) {
                    require(expected.code == Code.TLS_TIMEOUT, "TLS timeout misclassified");
                }
            }
        });
        check("report uses a field whitelist", () -> {
            Binding binding = new Binding();
            binding.network = "token=private-network.invalid";
            Report report = run(request("secret-host.invalid", 1, false, "secret-sni.invalid"), binding);
            String text = ReportFormatter.markdown(report);
            require(!text.contains("secret-host") && !text.contains("secret-sni") && !text.contains("private-network"), "identifier leaked");
            Step proxy = report.steps.stream().filter(step -> step.stage == Stage.PROXY).findFirst().orElseThrow();
            require(proxy.code == Code.CORE_UNAVAILABLE && proxy.state == State.UNKNOWN, "missing core-unavailable evidence");
            require(!text.contains("CORE_UNAVAILABLE") && !text.contains("diagnostic_")
                && text.contains(proxy.code.message()), "missing localized limitation");
        });

        if (args.length != 2) throw new IllegalArgumentException("Pass the test-only PKCS12 and diagnostic resource paths");
        SSLContext trusted = context(args[0]);
        DiagnosticEngine tlsEngine = new DiagnosticEngine(new SocketProbe(trusted.getSocketFactory()), new CoreAdapter.Unavailable());
        check("real TLS handshake with hostname verification", () -> {
            try (Peer peer = Peer.tls(trusted)) {
                Report report = tlsEngine.run(request("private.invalid", peer.port(), true, "localhost"),
                    Binding::new, new RunControl(5000), steps -> {});
                has(report, Stage.TLS, State.PASSED, Code.TLS_OK);
                unknownCore(report);
            }
        });
        check("trusted certificate with wrong hostname is rejected", () -> {
            try (Peer peer = Peer.tls(trusted)) {
                Report report = tlsEngine.run(request("private.invalid", peer.port(), true, "wrong.invalid"),
                    Binding::new, new RunControl(5000), steps -> {});
                has(report, Stage.TLS, State.FAILED, Code.TLS_CERTIFICATE);
            }
        });
        check("production trust store rejects test self-signed certificate", () -> {
            try (Peer peer = Peer.tls(trusted)) {
                has(run(request("private.invalid", peer.port(), true, "localhost"), new Binding()),
                    Stage.TLS, State.FAILED, Code.TLS_CERTIFICATE);
            }
        });
        check("DNS-only command never opens a TCP socket", () -> {
            Binding binding = new Binding();
            Report report = run(new Request("private.invalid", 443, false, "", Family.AUTO, true), binding);
            require(binding.resolutions.get() == 1 && binding.sockets.get() == 0, "DNS-only command performed another probe");
            has(report, Stage.PROXY, State.SKIPPED, Code.DNS_ONLY);
        });
        check("verified proxy success preserves an auxiliary TLS difference", () -> {
            Report report = new Report("run-01", "2026-10-21", "Wi-Fi", Arrays.asList(
                new Step(Stage.TLS, State.FAILED, Code.TLS_CERTIFICATE, 20, "", 0),
                new Step(Stage.PROXY, State.PASSED, Code.PROXY_OK, 30, "", 0, 204)), 50);
            require(report.conclusion().contains("实际代理访问已验证"), "auxiliary failure hid verified proxy access");
            require(ReportFormatter.markdown(report).contains("HTTP 204"), "HTTP evidence omitted");
        });
        check("HTTP evidence is bounded to valid numeric status codes", () -> {
            Step step = new Step(Stage.PROXY, State.FAILED, Code.PROXY_HTTP_STATUS, 1, "", 0, 99999);
            require(step.httpStatus == 0, "invalid status escaped the whitelist");
        });
        check("reachable entrance plus proxy timeout narrows the next action", () -> {
            Report report = new Report("run-01", "2026-10-21", "Wi-Fi", Arrays.asList(
                new Step(Stage.TCP, State.PASSED, Code.TCP_OK, 1, "", 0),
                new Step(Stage.TLS, State.PASSED, Code.TLS_OK, 1, "", 0),
                new Step(Stage.PROXY, State.FAILED, Code.PROXY_TIMEOUT, 20, "", 0)), 22);
            require(report.conclusion().contains("辅助 TLS 已通过") && report.conclusion().contains("更换 HTTPS 目标"), "layered evidence not reflected in guidance");
        });
        check("unexpected target HTTP status does not label the node broken", () -> {
            Report report = new Report("run-01", "2026-10-21", "Wi-Fi", Collections.singletonList(
                new Step(Stage.PROXY, State.FAILED, Code.PROXY_HTTP_STATUS, 1, "", 0, 503)), 1);
            require(report.conclusion().contains("503") && report.conclusion().contains("更换 HTTPS"), "HTTP evidence omitted from conclusion");
        });
        check("observed proxy and target failures take priority over auxiliary failures", () -> {
            for (Code code : Arrays.asList(Code.PROXY_CERTIFICATE, Code.TARGET_CERTIFICATE, Code.PROXY_QUIC_TIMEOUT, Code.PROXY_QUIC_TLS, Code.PROXY_DNS, Code.PROXY_CHANNEL_TIMEOUT, Code.PROXY_CHANNEL_FAILED)) {
                Report report = new Report("run-01", "2026-10-22", "Wi-Fi", Arrays.asList(
                    new Step(Stage.TLS, State.FAILED, Code.TLS_FAILED, 1, "", 0),
                    new Step(Stage.PROXY, State.FAILED, code, 2, "", 0)), 3);
                require(report.conclusion().equals(code.message()), "specific actual failure hidden by auxiliary probe");
            }
        });
        check("configured unverified node policy remains visible on successful reports", () -> {
            Report report = new Report("run-01", "2026-10-22", "Wi-Fi", Arrays.asList(
                new Step(Stage.POLICY, State.SKIPPED, Code.NODE_CERTIFICATE_UNVERIFIED, 0, "", 0),
                new Step(Stage.PROXY, State.PASSED, Code.PROXY_OK, 2, "", 0, 204)), 2);
            require(report.conclusion().equals(Code.PROXY_OK.message()), "certificate setting replaced connectivity outcome");
            require(ReportFormatter.markdown(report).contains(Code.NODE_CERTIFICATE_UNVERIFIED.message()), "policy omitted from report");
        });
        check("changed configuration takes priority over internal cancellation", () -> {
            Report report = new Report("run-01", "2026-10-21", "Wi-Fi", Arrays.asList(
                new Step(Stage.PROXY, State.CANCELLED, Code.CANCELLED, 1, "", 0),
                new Step(Stage.PROXY, State.STALE, Code.CONFIG_CHANGED, 1, "", 0)), 2);
            require(report.conclusion().equals(Code.CONFIG_CHANGED.message()), "configuration invalidation reported as user cancellation");
        });
        System.out.println("PASS: " + passed + " core checks; all network tests used loopback only.");
    }

    private static Request request(String host, int port, boolean tls, String sni) {
        return new Request(host, port, tls, sni, Family.AUTO);
    }

    private static Report run(Request request, Binding binding) {
        return new DiagnosticEngine().run(request, () -> binding, new RunControl(5000), steps -> {});
    }

    private static void has(Report report, Stage stage, State state, Code code) {
        require(report.steps.stream().anyMatch(s -> s.stage == stage && s.state == state && s.code == code),
            "missing " + stage + "/" + state + "/" + code + " in " + ReportFormatter.markdown(report));
    }

    private static void unknownCore(Report report) { has(report, Stage.PROXY, State.UNKNOWN, Code.CORE_UNAVAILABLE); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Check action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }
    private static void check(String name, Check action) throws Exception {
        action.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static class Binding implements NetworkBinding {
        List<InetAddress> addresses = Collections.singletonList(LOOPBACK);
        String network = "Wi-Fi";
        boolean closed;
        AtomicInteger sockets = new AtomicInteger();
        AtomicInteger resolutions = new AtomicInteger();
        @Override public String networkType() { return network; }
        @Override public boolean isCurrent() { return true; }
        @Override public List<InetAddress> resolve(String host, int timeout, RunControl control) throws Exception {
            resolutions.incrementAndGet();
            return addresses;
        }
        @Override public Socket createSocket() {
            sockets.incrementAndGet();
            return new Socket();
        }
        @Override public void close() { closed = true; }
    }

    private static SSLContext context(String path) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(path)) { keys.load(input, "test-only".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server;
        final CountDownLatch accepted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        volatile Socket client;
        final Thread worker;

        Peer(ServerSocket server, boolean stall, boolean tls) {
            this.server = server;
            worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    client = socket;
                    accepted.countDown();
                    if (tls) ((SSLSocket) socket).startHandshake();
                    if (stall) done.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            }, "test-loopback-peer");
            worker.setDaemon(true);
            worker.start();
        }

        static Peer tcp(boolean stall) throws IOException { return new Peer(new ServerSocket(0, 4, LOOPBACK), stall, false); }
        static Peer tls(SSLContext context) throws IOException {
            SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            server.bind(new InetSocketAddress(LOOPBACK, 0));
            return new Peer(server, false, true);
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            done.countDown();
            server.close();
            Socket socket = client;
            if (socket != null) socket.close();
            worker.join(2000);
        }
    }
}

