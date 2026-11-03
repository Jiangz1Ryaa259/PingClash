// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps what runs outside the app in step with the settings: the background update job, the Wi-Fi
 * rule, the Quick Settings tile and the home-screen widget.
 */
internal object Automation {
    private const val WIDGET_MIN_MS = 2000L

    fun start(app: PingClashApplication) {
        val repository = app.repository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            repository.data
                .map { it.client.subscriptionHours to it.profiles.any(LocalProfile::autoUpdates) }
                .distinctUntilChanged()
                .collect { (hours, any) ->
                    runCatching { SubscriptionUpdateJob.schedule(app, hours, any) }
                }
        }
        scope.launch {
            repository.data
                .map { it.client.wifiAuto }
                .distinctUntilChanged()
                .collect { enabled ->
                    withContext(Dispatchers.Main) { WifiAutoConnect.watch(app, enabled) }
                }
        }
        scope.launch {
            // A network added to (or removed from) the list applies at once.
            repository.data
                .map { it.client.trustedWifi }
                .distinctUntilChanged()
                .drop(1)
                .collect { WifiAutoConnect.settle() }
        }
        scope.launch {
            combine(
                    VpnRuntime.state.map { it.phase }.distinctUntilChanged(),
                    repository.data.map { it.activeProfile?.name }.distinctUntilChanged(),
                ) { _, _ ->
                    Unit
                }
                .collect {
                    ConnectTileService.refresh(app)
                    ConnectWidget.refresh(app)
                }
        }
        scope.launch {
            // Speed changes every second; the widget follows at most every two (a state flow
            // hands a slow collector only the latest value).
            VpnRuntime.state
                .onEach {
                    if (it.stage == VpnPhase.ESTABLISHED) ConnectWidget.refresh(app)
                    delay(WIDGET_MIN_MS)
                }
                .collect()
        }
    }
}
