// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Event-driven foreground maintenance of subscriptions (daily) and their providers. No background
 * minute alarm/poll loop.
 */
internal class ProviderRefreshCoordinator(
    private val app: Application,
    private val repository: ProfileRepository,
) {
    private val foreground = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var visible = 0
    // In-memory backoff; a failed or reverted update must not retry every recheck.
    private val subscriptionAttempts = mutableMapOf<String, Long>()

    fun start() {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    visible++
                    foreground.value = visible > 0
                }

                override fun onActivityStopped(activity: Activity) {
                    visible = (visible - 1).coerceAtLeast(0)
                    foreground.value = visible > 0
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
        scope.launch {
            combine(
                    foreground,
                    networkAvailable(),
                    VpnRuntime.state.map { it.active }.distinctUntilChanged(),
                    repository.data.map { it.profiles }.distinctUntilChanged(),
                ) { shown, connected, _, profiles ->
                    // Also while the VPN runs: downloads follow the tunnel, and the result is
                    // applied through the hot routing update instead of waiting for a reconnect.
                    profiles.filter { shown && connected && (it.remote != null || it.autoUpdates) }
                }
                .collectLatest { profiles ->
                    if (profiles.isEmpty()) return@collectLatest
                    delay(INITIAL_DELAY)
                    RuleData.prepare(app).get()
                    while (true) {
                        profiles.forEach { profile ->
                            if (subscriptionDelay(profile) == 0L) {
                                subscriptionAttempts[profile.id] = now()
                                // Failures wait for the retry backoff; manual refresh shows errors.
                                runCatching {
                                        runInterruptible { repository.refreshSubscription(profile) }
                                    }
                                    .onFailure {
                                        if (it is kotlinx.coroutines.CancellationException) throw it
                                    }
                            }
                            runInterruptible { repository.refreshProviders(profile) }
                        }
                        runInterruptible { repository.applyPendingProvidersLive() }
                        val seconds =
                            minOf(
                                profiles
                                    .mapNotNull { it.remote }
                                    .minOfOrNull(RemoteProfiles::nextDelaySeconds)
                                    ?: Long.MAX_VALUE,
                                profiles.minOf(::subscriptionDelay),
                            )
                        if (seconds == Long.MAX_VALUE) return@collectLatest
                        delay(seconds.coerceAtLeast(MIN_RECHECK_SECONDS) * MILLIS_PER_SECOND)
                    }
                }
        }
    }

    private fun now() = java.time.Instant.now().epochSecond

    private fun subscriptionDelay(profile: LocalProfile): Long {
        if (!profile.autoUpdates) return Long.MAX_VALUE
        val now = now()
        val updated = profile.subscriptionUpdated.takeIf { it <= now } ?: 0
        val attempted = subscriptionAttempts[profile.id] ?: 0
        return maxOf(
                updated + SUBSCRIPTION_INTERVAL_SECONDS - now,
                attempted + SUBSCRIPTION_RETRY_SECONDS - now,
            )
            .coerceAtLeast(0)
    }

    private fun networkAvailable() =
        callbackFlow {
                val manager = app.getSystemService(ConnectivityManager::class.java)
                fun update() {
                    trySend(
                        manager
                            .getNetworkCapabilities(manager.activeNetwork)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    )
                }
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = update()

                        override fun onLost(network: Network) = update()

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) = update()
                    }
                update()
                manager.registerDefaultNetworkCallback(callback)
                awaitClose { manager.unregisterNetworkCallback(callback) }
            }
            .distinctUntilChanged()
}

private const val INITIAL_DELAY = 5000L
private const val MIN_RECHECK_SECONDS = 30L
private const val SUBSCRIPTION_INTERVAL_SECONDS = 24 * 60 * 60L
private const val SUBSCRIPTION_RETRY_SECONDS = 30 * 60L
private const val MILLIS_PER_SECOND = 1000L
