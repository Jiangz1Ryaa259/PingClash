package dev.pingclash.diagnostics;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import dev.pingclash.diagnostics.Models.Code;
import dev.pingclash.diagnostics.Models.State;

public final class RunControl implements Closeable {
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pingclash-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final long deadline;
    private final ScheduledFuture<?> expiry;
    private final Set<Closeable> resources = new HashSet<>();
    private Code stopCode;

    public RunControl(long totalMillis) {
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, totalMillis));
        expiry = CLOCK.schedule(this::expire, Math.max(1, totalMillis), TimeUnit.MILLISECONDS);
    }

    public synchronized void check() throws ProbeFailure {
        if (stopCode == Code.CANCELLED) throw new ProbeFailure(State.CANCELLED, Code.CANCELLED);
        if (stopCode == Code.DEADLINE || System.nanoTime() >= deadline)
            throw new ProbeFailure(State.FAILED, Code.DEADLINE);
    }

    public synchronized int timeoutMillis(long stageMillis) throws ProbeFailure {
        check();
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        return (int) Math.max(1, Math.min(stageMillis, remaining));
    }

    public synchronized <T extends Closeable> T track(T resource) throws ProbeFailure {
        try { check(); } catch (ProbeFailure failure) {
            quietlyClose(resource);
            throw failure;
        }
        resources.add(resource);
        return resource;
    }

    public synchronized void release(Closeable resource) {
        resources.remove(resource);
        quietlyClose(resource);
    }

    public synchronized void cancel() {
        if (stopCode == null) stopCode = Code.CANCELLED;
        close();
    }

    private synchronized void expire() {
        if (stopCode == null) stopCode = Code.DEADLINE;
        closeResources();
    }

    // Closing a finished run releases resources without turning successful results into cancellation.
    @Override public synchronized void close() {
        expiry.cancel(false);
        closeResources();
    }

    private void closeResources() {
        for (Closeable resource : resources) quietlyClose(resource);
        resources.clear();
    }

    private static void quietlyClose(Closeable resource) {
        try { resource.close(); } catch (Exception ignored) { }
    }
}

