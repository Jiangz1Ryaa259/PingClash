// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.pingclash.mobile.Mobile
import dev.pingclash.mobile.Platform
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class PingClashApplication : Application() {
    lateinit var repository: ProfileRepository
        private set

    var nativeReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "development"
        CrashRecorder.install(this, version)
        UiStrings.initialize(this)
        dev.pingclash.diagnostics.DiagnosticText.install { key ->
            diagnosticResources[key]?.let { tr(it) } ?: key
        }
        dev.pingclash.diagnostics.DiagnosticText.setVersion(version)
        nativeReady =
            runCatching {
                    go.Seq.setContext(this)
                    Mobile.setPlatform(AndroidCorePlatform(this))
                    org.json.JSONObject(Mobile.info()).getString("engine") == "mihomo"
                }
                .getOrDefault(false)
        if (nativeReady) CrashRecorder.armCore(this)
        if (nativeReady) RuleData.prepare(this)
        repository = ProfileRepository(this)
        UiStrings.migrate(repository.data.value.client.language)
        if (nativeReady) ProviderRefreshCoordinator(this, repository).start()
    }
}

internal class AndroidCorePlatform(context: Application) : Platform {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val packages = context.packageManager

    fun underlying(): Network? {
        fun eligible(network: Network) =
            manager.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        PingVpnService.instance?.let {
            return it.boundNetwork?.takeIf(::eligible)
        }
        // Follow the current system route; never silently bypass another VPN or
        // choose a different physical network when the current one is absent.
        return manager.activeNetwork?.takeIf(::eligible)
    }

    internal fun outbound(): Network? {
        if (PingVpnService.instance != null) return underlying()
        return manager.activeNetwork?.takeIf { network ->
            manager
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    override fun protect(fd: Long): Boolean =
        runCatching {
                val network = outbound() ?: return false
                val vpn = PingVpnService.instance
                if (vpn != null && !vpn.protect(fd.toInt())) return false
                ParcelFileDescriptor.fromFd(fd.toInt()).use {
                    network.bindSocket(it.fileDescriptor)
                }
                true
            }
            .getOrDefault(false)

    // Only queried for an actual UID/process rule on a live VPN flow. No history,
    // installed-app enumeration, /proc reading, or telemetry is performed.
    override fun connectionOwner(
        protocol: Long,
        sourceAddress: String,
        destinationAddress: String,
    ): String =
        runCatching {
                if (PingVpnService.instance == null) return ""
                val owner =
                    manager.getConnectionOwnerUid(
                        protocol.toInt(),
                        ownerEndpoint(sourceAddress),
                        ownerEndpoint(destinationAddress),
                    )
                if (owner < 0) return ""
                val name =
                    runCatching {
                            packages.getPackagesForUid(owner)?.sorted()?.firstOrNull().orEmpty()
                        }
                        .getOrDefault("")
                "$owner\n$name"
            }
            .getOrDefault("")

    private fun ownerEndpoint(raw: String): java.net.InetSocketAddress {
        val uri = java.net.URI("tcp://$raw")
        require(uri.port in 1..MAX_OWNER_PORT)
        val host = requireNotNull(uri.host).removePrefix("[").removeSuffix("]")
        return java.net.InetSocketAddress(
            android.net.InetAddresses.parseNumericAddress(host),
            uri.port,
        )
    }

    override fun lookup(host: String, timeoutMillis: Long): String {
        val network = outbound() ?: return ""
        val signal = CancellationSignal()
        val answer = CompletableFuture<List<InetAddress>>()
        return try {
            DnsResolver.getInstance()
                .query(
                    network,
                    host,
                    DnsResolver.FLAG_EMPTY,
                    Executor { it.run() },
                    signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(values: List<InetAddress>, rcode: Int) {
                            if (rcode == 0) answer.complete(values)
                            else answer.complete(emptyList())
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            answer.complete(emptyList())
                        }
                    },
                )
            answer.get(timeoutMillis.coerceIn(1, 5000), TimeUnit.MILLISECONDS).joinToString("\n") {
                it.hostAddress.orEmpty()
            }
        } catch (_: Exception) {
            ""
        } finally {
            signal.cancel()
        }
    }
}

private const val MAX_OWNER_PORT = 65535
