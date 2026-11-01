package dev.pingclash.diagnostics;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import dev.pingclash.diagnostics.Models.*;

public final class DiagnosticEngine {
    public interface BindingFactory { NetworkBinding open() throws ProbeFailure; }
    public interface Observer { void update(List<Step> steps); }

    private final SocketProbe socketProbe;
    private final CoreAdapter core;

    public DiagnosticEngine() { this(new SocketProbe(), new CoreAdapter.Unavailable()); }
    public DiagnosticEngine(SocketProbe socketProbe, CoreAdapter core) {
        this.socketProbe = socketProbe;
        this.core = core;
    }

    public Report run(Request request, BindingFactory factory, RunControl control, Observer observer) {
        long started = System.nanoTime();
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String networkType = DiagnosticText.text("diagnostic_4d8c1c5b42");
        List<Step> steps = new ArrayList<>();
        Stage stage = Stage.NETWORK;
        long stageStart = started;
        try {
            control.check();
            NetworkBinding binding = control.track(factory.open());
            networkType = binding.networkType();
            ensureCurrent(binding, control);
            add(steps, observer, new Step(stage, State.PASSED, Code.NETWORK_READY, elapsed(stageStart), "", 0));

            stage = Stage.DNS;
            stageStart = System.nanoTime();
            List<InetAddress> addresses;
            if (Models.isLiteral(request.host)) {
                addresses = Collections.singletonList(InetAddress.getByName(request.host));
            } else {
                try {
                    addresses = binding.resolve(request.host, control.timeoutMillis(3000), control);
                } catch (ProbeFailure e) {
                    throw e;
                } catch (SecurityException e) {
                    throw new ProbeFailure(State.BLOCKED, Code.LOCAL_RESTRICTION);
                } catch (TimeoutException | SocketTimeoutException e) {
                    throw new ProbeFailure(State.FAILED, Code.DNS_TIMEOUT);
                } catch (Exception e) {
                    control.check();
                    throw new ProbeFailure(State.FAILED, Code.DNS_FAILED);
                }
            }
            ensureCurrent(binding, control);
            List<InetAddress> candidates = new ArrayList<>();
            for (InetAddress address : addresses) {
                if (request.family == Family.AUTO || request.family == Family.IPV4 && address instanceof Inet4Address
                    || request.family == Family.IPV6 && address instanceof Inet6Address) {
                    if (!candidates.contains(address)) candidates.add(address);
                }
            }
            if (candidates.isEmpty()) throw new ProbeFailure(State.FAILED, Code.NO_MATCHING_ADDRESS);
            if (request.family == Family.AUTO) candidates = interleaveFamilies(candidates);
            add(steps, observer, new Step(stage, Models.isLiteral(request.host) ? State.SKIPPED : State.PASSED,
                Models.isLiteral(request.host) ? Code.LITERAL_ADDRESS : Code.DNS_OK, elapsed(stageStart), "", 0));

            if (request.dnsOnly) {
                add(steps, observer, new Step(Stage.TCP, State.SKIPPED, Code.DNS_ONLY, 0, "", 0));
                add(steps, observer, new Step(Stage.TLS, State.SKIPPED, Code.DNS_ONLY, 0, "", 0));
                add(steps, observer, new Step(Stage.PROXY, State.SKIPPED, Code.DNS_ONLY, 0, "", 0));
                return new Report(UUID.randomUUID().toString(), timestamp, networkType, steps, elapsed(started));
            }

            stage = Stage.TCP;
            long tcpDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(control.timeoutMillis(4000));
            Socket connected = null;
            String connectedFamily = "";
            int attempt = 0;
            for (InetAddress address : candidates) {
                ensureCurrent(binding, control);
                long remaining = TimeUnit.NANOSECONDS.toMillis(tcpDeadline - System.nanoTime());
                if (remaining <= 0) break;
                stageStart = System.nanoTime();
                attempt++;
                String family = address instanceof Inet6Address ? "IPv6" : "IPv4";
                try {
                    // Reserve part of the TCP stage for a second candidate when one exists.
                    int timeout = (int) Math.max(1, Math.min(remaining,
                        attempt < candidates.size() ? 2000 : remaining));
                    connected = socketProbe.connect(binding, address, request.port, timeout, control);
                    ensureCurrent(binding, control);
                    connectedFamily = family;
                    add(steps, observer, new Step(stage, State.PASSED, Code.TCP_OK, elapsed(stageStart), family, attempt));
                    break;
                } catch (ProbeFailure failure) {
                    ensureCurrent(binding, control);
                    if (failure.state != State.FAILED || failure.code == Code.DEADLINE) throw failure;
                    add(steps, observer, new Step(stage, failure.state, failure.code, elapsed(stageStart), family, attempt));
                }
            }
            if (connected == null) {
                if (attempt == 0) add(steps, observer, new Step(Stage.TCP, State.FAILED, Code.TCP_TIMEOUT, 0, "", 0));
                add(steps, observer, new Step(Stage.TLS, State.SKIPPED, Code.DEPENDENCY_FAILED, 0, "", 0));
            } else {
                stage = Stage.TLS;
                stageStart = System.nanoTime();
                if (!request.useTls) {
                    add(steps, observer, new Step(stage, State.SKIPPED, Code.TLS_DISABLED, 0, connectedFamily, attempt));
                } else {
                    socketProbe.handshake(connected, request, control.timeoutMillis(5000), control);
                    ensureCurrent(binding, control);
                    add(steps, observer, new Step(stage, State.PASSED, Code.TLS_OK, elapsed(stageStart), connectedFamily, attempt));
                }
            }
            ensureCurrent(binding, control);
        } catch (ProbeFailure failure) {
            add(steps, observer, new Step(stage, failure.state, failure.code, elapsed(stageStart), "", 0));
            fillDependencies(steps, observer);
        } catch (Exception failure) {
            try {
                control.check();
                add(steps, observer, new Step(stage, State.FAILED, Code.INTERNAL_ERROR, elapsed(stageStart), "", 0));
            } catch (ProbeFailure stop) {
                add(steps, observer, new Step(stage, stop.state, stop.code, elapsed(stageStart), "", 0));
            }
            fillDependencies(steps, observer);
        } finally {
            control.close();
        }
        add(steps, observer, core.proxyStatus());
        return new Report(UUID.randomUUID().toString(), timestamp, networkType, steps, elapsed(started));
    }

    private static void ensureCurrent(NetworkBinding binding, RunControl control) throws ProbeFailure {
        control.check();
        if (!binding.isCurrent()) throw new ProbeFailure(State.STALE, Code.NETWORK_CHANGED);
    }

    private static List<InetAddress> interleaveFamilies(List<InetAddress> candidates) {
        List<InetAddress> ipv4 = new ArrayList<>();
        List<InetAddress> ipv6 = new ArrayList<>();
        for (InetAddress address : candidates) {
            if (address instanceof Inet6Address) ipv6.add(address); else ipv4.add(address);
        }
        List<InetAddress> first = candidates.get(0) instanceof Inet6Address ? ipv6 : ipv4;
        List<InetAddress> second = first == ipv6 ? ipv4 : ipv6;
        List<InetAddress> ordered = new ArrayList<>();
        for (int index = 0; index < Math.max(first.size(), second.size()); index++) {
            if (index < first.size()) ordered.add(first.get(index));
            if (index < second.size()) ordered.add(second.get(index));
        }
        return ordered;
    }

    private static void fillDependencies(List<Step> steps, Observer observer) {
        for (Stage next : new Stage[] {Stage.DNS, Stage.TCP, Stage.TLS}) {
            boolean present = false;
            for (Step step : steps) if (step.stage == next) present = true;
            if (!present) add(steps, observer, new Step(next, State.SKIPPED, Code.DEPENDENCY_FAILED, 0, "", 0));
        }
    }

    private static void add(List<Step> steps, Observer observer, Step step) {
        steps.add(step);
        observer.update(Collections.unmodifiableList(new ArrayList<>(steps)));
    }

    private static long elapsed(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }
}

