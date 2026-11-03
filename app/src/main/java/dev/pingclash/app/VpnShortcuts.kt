// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.VpnService

/**
 * Starting and stopping the VPN from outside the app: the Quick Settings tile, the home-screen
 * widget, start on boot and Wi-Fi rules. None of them can ask for the VPN permission; when Android
 * has not allowed this VPN yet, or there is nothing to connect with, the app has to open first.
 */
internal object VpnShortcuts {
    /** True when a connect needs no screen: VPN allowed, the core loaded and a profile chosen. */
    fun canStart(context: Context): Boolean {
        val app = context.applicationContext as PingClashApplication
        val data = app.repository.data.value
        return app.nativeReady &&
            !data.loadFailed &&
            data.activeProfile != null &&
            runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
    }

    /**
     * Connects without a screen. When Android refuses (or connecting needs the app), a notification
     * offers to open it instead; returns false then.
     */
    fun start(context: Context): Boolean =
        when {
            VpnRuntime.state.value.active -> true
            !canStart(context) -> {
                offerToOpen(context)
                false
            }
            else ->
                runCatching { VpnRuntime.connect(context) }
                    .onFailure { offerToOpen(context) }
                    .isSuccess
        }

    fun stop(context: Context) = VpnRuntime.disconnect(context)

    /** The tile's and widget's tap: stop when on, otherwise start. */
    fun toggle(context: Context): Boolean =
        if (VpnRuntime.state.value.active) {
            stop(context)
            true
        } else start(context)

    private fun offerToOpen(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                tr(R.string.ui_auto_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                vpnActivityIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_status)
                    .setContentTitle(tr(R.string.ui_auto_open_title))
                    .setContentText(tr(R.string.ui_auto_open_body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private const val CHANNEL = "automation"
    private const val NOTIFICATION_ID = 7
}
