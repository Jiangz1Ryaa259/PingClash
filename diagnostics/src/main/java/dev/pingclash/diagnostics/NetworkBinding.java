package dev.pingclash.diagnostics;

import java.net.InetAddress;
import java.net.Socket;
import java.io.IOException;
import java.util.List;

public interface NetworkBinding extends java.io.Closeable {
    String networkType();
    boolean isCurrent();
    List<InetAddress> resolve(String host, int timeoutMillis, RunControl control) throws Exception;
    Socket createSocket() throws IOException;
    default Models.Code classifyConnectFailure(Exception failure) {
        if (failure instanceof SecurityException) return Models.Code.LOCAL_RESTRICTION;
        if (failure instanceof java.net.SocketTimeoutException) return Models.Code.TCP_TIMEOUT;
        // ConnectException also represents unreachable paths. Classify a refusal only with evidence.
        if (failure instanceof java.net.ConnectException && failure.getMessage() != null &&
            failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("refused")) {
            return Models.Code.TCP_REFUSED;
        }
        return Models.Code.TCP_FAILED;
    }
    @Override default void close() { }
}

