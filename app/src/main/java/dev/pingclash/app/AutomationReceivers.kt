// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Connects after the phone starts, when the user turned that on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PingClashApplication
        val settings = app.repository.data.value.client
        if (!settings.startOnBoot) return
        val pending = goAsync()
        thread(name = "boot-connect") {
            try {
                // A trusted Wi-Fi network wins: the user asked to stay direct there.
                if (!settings.wifiAuto || WifiAutoConnect.currentRule(context) != WifiRule.TRUSTED)
                    VpnShortcuts.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Wakes the app when a network appears, so Wi-Fi rules apply while it is closed. */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiAutoConnect.ACTION) return
        val pending = goAsync()
        thread(name = "wifi-rule") {
            try {
                WifiAutoConnect.settleAndApply(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The widget's switch; not exported, so only the widget's own pending intent reaches it. */
class WidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectWidget.TOGGLE) return
        VpnShortcuts.toggle(context)
        ConnectWidget.refresh(context)
    }
}
