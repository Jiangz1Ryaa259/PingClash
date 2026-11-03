// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The Quick Settings tile: shows the connection and switches it with one tap. */
class ConnectTileService : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = CoroutineScope(Dispatchers.Main).launch { VpnRuntime.state.collect(::render) }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnRuntime.state.value
        when {
            state.active -> VpnShortcuts.stop(this)
            // The VPN permission and the first profile can only be granted in the app.
            !VpnShortcuts.canStart(this) || !VpnShortcuts.start(this) -> openApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = vpnActivityIntent(this)
        if (Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE)
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }

    private fun render(state: VpnUiState) {
        val tile = qsTile ?: return
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "PingClash"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_status)
        tile.subtitle = shortcutStatus(state)
        tile.updateTile()
    }

    companion object {
        private const val UPSIDE_DOWN_CAKE = 34

        /** Asks Quick Settings to redraw the tile after a change made elsewhere. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, ConnectTileService::class.java),
                )
            }
        }
    }
}

/** One word for the tile and widget: connected, connecting, stopping or off. */
internal fun shortcutStatus(state: VpnUiState): String =
    tr(
        when (state.stage) {
            VpnPhase.ESTABLISHED -> R.string.ui_shortcut_on
            VpnPhase.CONNECTING -> R.string.ui_shortcut_connecting
            VpnPhase.STOPPING -> R.string.ui_shortcut_stopping
            else -> R.string.ui_shortcut_off
        }
    )
