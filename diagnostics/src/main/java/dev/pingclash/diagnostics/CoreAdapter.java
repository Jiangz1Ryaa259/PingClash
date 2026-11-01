package dev.pingclash.diagnostics;

/** Pure-Java diagnostic boundary; Android owns the native bridge and its lifecycle. */
public interface CoreAdapter {
    boolean isAvailable();
    Models.Step proxyStatus();

    final class NotRequested implements CoreAdapter {
        @Override public boolean isAvailable() { return true; }
        @Override public Models.Step proxyStatus() {
            return new Models.Step(Models.Stage.PROXY, Models.State.SKIPPED,
                Models.Code.PROXY_NOT_REQUESTED, 0, "", 0);
        }
    }

    final class Unavailable implements CoreAdapter {
        @Override public boolean isAvailable() { return false; }
        @Override public Models.Step proxyStatus() {
            return new Models.Step(Models.Stage.PROXY, Models.State.UNKNOWN,
                Models.Code.CORE_UNAVAILABLE, 0, "", 0);
        }
    }
}

