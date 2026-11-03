// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The home-screen widget: the connection's state, the current speed and a switch. The switch
 * connects in place when Android already allows this VPN; otherwise it opens the app.
 */
class ConnectWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        const val TOGGLE = "dev.pingclash.app.WIDGET_TOGGLE"

        /** Redraws every placed widget; cheap when there is none. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids =
                runCatching {
                        manager.getAppWidgetIds(ComponentName(context, ConnectWidget::class.java))
                    }
                    .getOrNull()
            if (ids == null || ids.isEmpty()) return
            render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val state = VpnRuntime.state.value
            val views = RemoteViews(context.packageName, R.layout.widget_connect)
            views.setTextViewText(R.id.widget_status, shortcutStatus(state))
            views.setTextViewText(R.id.widget_detail, detail(context, state))
            views.setInt(
                R.id.widget_toggle,
                "setBackgroundResource",
                if (state.active) R.drawable.widget_toggle_on else R.drawable.widget_toggle_off,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                tr(if (state.active) R.string.ui_widget_disconnect else R.string.ui_widget_connect),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    vpnActivityIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val toggle =
                if (state.active || VpnShortcuts.canStart(context))
                    PendingIntent.getBroadcast(
                        context,
                        1,
                        // Not this exported provider: other apps must not reach the switch.
                        Intent(context, WidgetToggleReceiver::class.java).setAction(TOGGLE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                // The VPN permission or the first profile needs the app.
                else open
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_toggle, toggle)
            runCatching { manager.updateAppWidget(ids, views) }
        }

        private fun detail(context: Context, state: VpnUiState): String =
            when (state.stage) {
                VpnPhase.ESTABLISHED ->
                    "↓ ${formatBytes(state.downloadSpeed)}/s · ↑ ${formatBytes(state.uploadSpeed)}/s"
                VpnPhase.CONNECTING,
                VpnPhase.STOPPING -> state.message
                else ->
                    (context.applicationContext as PingClashApplication)
                        .repository
                        .data
                        .value
                        .activeProfile
                        ?.name ?: tr(R.string.ui_widget_no_profile)
            }
    }
}
