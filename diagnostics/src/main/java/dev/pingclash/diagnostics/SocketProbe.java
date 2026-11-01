package dev.pingclash.diagnostics;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import dev.pingclash.diagnostics.Models.*;

public final class SocketProbe {
    private final SSLSocketFactory tlsFactory;

    public SocketProbe() { this((SSLSocketFactory) SSLSocketFactory.getDefault()); }
    public SocketProbe(SSLSocketFactory factory) { tlsFactory = factory; }

    public Socket connect(NetworkBinding binding, InetAddress address, int port,
                          int timeout, RunControl control) throws ProbeFailure {
        Socket socket = null;
        try {
            control.check();
            socket = control.track(binding.createSocket());
            socket.connect(new InetSocketAddress(address, port), timeout);
            control.check();
            return socket;
        } catch (Exception failure) {
            if (socket != null) control.release(socket);
            control.check();
            if (failure instanceof ProbeFailure) throw (ProbeFailure) failure;
            Code code = binding.classifyConnectFailure(failure);
            throw new ProbeFailure(code == Code.LOCAL_RESTRICTION ? State.BLOCKED : State.FAILED, code);
        }
    }

    public void handshake(Socket socket, Request request, int timeout, RunControl control) throws ProbeFailure {
        try {
            control.check();
            SSLSocket tls = control.track((SSLSocket) tlsFactory.createSocket(
                socket, request.serverName, request.port, true));
            tls.setSoTimeout(timeout);
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            if (!Models.isLiteral(request.serverName)) {
                params.setServerNames(Collections.singletonList(new SNIHostName(request.serverName)));
            }
            tls.setSSLParameters(params);
            tls.startHandshake();
            control.check();
        } catch (Exception failure) {
            control.check();
            if (failure instanceof ProbeFailure) throw (ProbeFailure) failure;
            if (failure instanceof SecurityException) throw new ProbeFailure(State.BLOCKED, Code.LOCAL_RESTRICTION);
            if (failure instanceof SocketTimeoutException) throw new ProbeFailure(State.FAILED, Code.TLS_TIMEOUT);
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof CertificateException) throw new ProbeFailure(State.FAILED, Code.TLS_CERTIFICATE);
            }
            throw new ProbeFailure(State.FAILED, Code.TLS_FAILED);
        }
    }
}

