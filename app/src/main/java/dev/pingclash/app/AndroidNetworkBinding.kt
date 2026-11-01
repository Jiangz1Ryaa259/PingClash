package dev.pingclash.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import dev.pingclash.diagnostics.Models.Code
import dev.pingclash.diagnostics.Models.State
import dev.pingclash.diagnostics.NetworkBinding
import dev.pingclash.diagnostics.ProbeFailure
import dev.pingclash.diagnostics.RunControl
import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidNetworkBinding
private constructor(
    private val manager: ConnectivityManager,
    private val network: Network,
    private val type: String,
) : NetworkBinding {
    private val invalidated = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(current: Network) {
                val ownVpn =
                    PingVpnService.instance != null &&
                        manager
                            .getNetworkCapabilities(current)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (current != network && !ownVpn) invalidated.set(true)
            }

            override fun onLost(current: Network) {
                if (current == network) invalidated.set(true)
            }
        }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    override fun networkType(): String = type

    override fun isCurrent(): Boolean =
        !closed.get() &&
            !invalidated.get() &&
            (manager.activeNetwork == network ||
                PingVpnService.instance?.boundNetwork == network) &&
            manager.getNetworkCapabilities(network)?.let {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true

    override fun createSocket(): Socket {
        val socket = Socket()
        var prepared = false
        try {
            PingVpnService.instance?.let {
                if (!it.protect(socket)) throw ProbeFailure(State.BLOCKED, Code.LOCAL_RESTRICTION)
            }
            network.bindSocket(socket)
            prepared = true
            return socket
        } finally {
            if (!prepared) socket.close()
        }
    }

    override fun classifyConnectFailure(failure: Exception): Code {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is ErrnoException) {
                return when (cause.errno) {
                    OsConstants.ECONNREFUSED -> Code.TCP_REFUSED
                    OsConstants.ETIMEDOUT -> Code.TCP_TIMEOUT
                    OsConstants.EACCES,
                    OsConstants.EPERM -> Code.LOCAL_RESTRICTION
                    else -> Code.TCP_FAILED
                }
            }
            cause = cause.cause
        }
        return super<NetworkBinding>.classifyConnectFailure(failure)
    }

    override fun resolve(host: String, timeoutMillis: Int, control: RunControl): List<InetAddress> {
        val result = CompletableFuture<List<InetAddress>>()
        val signal = CancellationSignal()
        val resource =
            control.track(
                Closeable {
                    signal.cancel()
                    result.cancel(false)
                }
            )
        try {
            DnsResolver.getInstance()
                .query(
                    network,
                    host,
                    DnsResolver.FLAG_EMPTY,
                    Executor { it.run() },
                    signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (rcode == 0) result.complete(answer)
                            else result.completeExceptionally(IllegalStateException("DNS_RCODE"))
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            result.completeExceptionally(error)
                        }
                    },
                )
            return result.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            control.release(resource)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    companion object {
        fun open(context: Context): NetworkBinding {
            try {
                val manager =
                    context.getSystemService(ConnectivityManager::class.java)
                        ?: throw ProbeFailure(State.BLOCKED, Code.NETWORK_UNAVAILABLE)
                val network =
                    PingVpnService.instance?.boundNetwork
                        ?: manager.activeNetwork
                        ?: throw ProbeFailure(State.BLOCKED, Code.NETWORK_UNAVAILABLE)
                val capabilities =
                    manager.getNetworkCapabilities(network)
                        ?: throw ProbeFailure(State.BLOCKED, Code.NETWORK_UNAVAILABLE)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    throw ProbeFailure(State.BLOCKED, Code.VPN_ACTIVE)
                }
                val type =
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                            tr(R.string.cellular)
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                            tr(R.string.ethernet)
                        else -> tr(R.string.other_network)
                    }
                return AndroidNetworkBinding(manager, network, type)
            } catch (_: SecurityException) {
                throw ProbeFailure(State.BLOCKED, Code.LOCAL_RESTRICTION)
            }
        }
    }
}
