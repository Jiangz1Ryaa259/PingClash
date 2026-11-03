// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Settings > Automation: start on boot, the tile, background updates and the Wi-Fi rule. */
@Composable
internal fun AutomationPage(s: SettingsDraft, editable: Boolean) {
    val draft = s.value
    val context = LocalContext.current
    SettingsSection(tr(R.string.ui_auto_group_start)) {
        SettingSwitch(tr(R.string.ui_auto_boot), draft.startOnBoot, editable) {
            s.change(draft.copy(startOnBoot = it))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RowDivider(underIcons = false)
            ActionRow(tr(R.string.ui_auto_add_tile), "auto-add-tile") { requestTile(context) }
        }
    }
    SettingsSection(tr(R.string.ui_auto_group_updates)) {
        PopUpChoice(
            tr(R.string.ui_auto_update_interval),
            draft.subscriptionHours.toString(),
            SUBSCRIPTION_HOURS.map { it.toString() to updateIntervalLabel(it) },
            editable,
        ) {
            s.change(draft.copy(subscriptionHours = it.toInt()))
        }
    }
    WifiSections(s, editable)
}

internal fun updateIntervalLabel(hours: Int): String =
    when {
        hours <= 0 -> tr(R.string.ui_auto_update_off)
        hours == HOURS_PER_DAY -> tr(R.string.ui_auto_update_daily)
        hours == HOURS_PER_WEEK -> tr(R.string.ui_auto_update_weekly)
        hours < HOURS_PER_DAY -> tr(R.string.ui_auto_update_hours, hours)
        else -> tr(R.string.ui_auto_update_days, hours / HOURS_PER_DAY)
    }

@Composable
private fun WifiSections(s: SettingsDraft, editable: Boolean) {
    val draft = s.value
    val context = LocalContext.current
    // Read again after a permission answer or a return from system settings.
    var checks by remember { mutableIntStateOf(0) }
    val foreground = rememberForeground()
    val fine = remember(checks, foreground) { WifiAutoConnect.locationAllowed(context, false) }
    val always = remember(checks, foreground) { WifiAutoConnect.locationAllowed(context) }
    val locationOn = remember(checks, foreground) { WifiAutoConnect.locationOn(context) }
    val current = remember(checks, foreground) { WifiAutoConnect.currentWifiName(context) }
    val askAlways =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    // Android 12+ shows the precise/approximate choice only when both are asked together; the
    // Wi-Fi name needs precise.
    val askFine =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checks++
            if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true)
                askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    SettingsSection(tr(R.string.ui_auto_group_wifi)) {
        SettingSwitch(tr(R.string.ui_auto_wifi), draft.wifiAuto, editable) { on ->
            s.change(draft.copy(wifiAuto = on))
            when {
                !on -> Unit
                !fine -> askFine.launch(LOCATION_PERMISSIONS)
                !always -> askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        if (draft.wifiAuto && !always) {
            RowDivider(underIcons = false)
            LinkRow(tr(R.string.ui_auto_location_always), "auto-location") {
                openSystem(
                    context,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
        if (draft.wifiAuto && !locationOn) {
            RowDivider(underIcons = false)
            LinkRow(tr(R.string.ui_auto_location_off), "auto-location-on") {
                openSystem(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
    }
    if (draft.wifiAuto) TrustedNetworks(s, editable, current)
}

/** The trusted list: remove a network, or add the one the phone is on now. */
@Composable
private fun TrustedNetworks(s: SettingsDraft, editable: Boolean, current: String?) {
    val draft = s.value
    SettingsSection(tr(R.string.ui_auto_trusted)) {
        draft.trustedWifi.forEach { name ->
            TrustedNetworkRow(name, editable) {
                s.change(draft.copy(trustedWifi = draft.trustedWifi - name))
            }
            RowDivider(underIcons = false)
        }
        ActionRow(
            current?.let { tr(R.string.ui_auto_add_current, it) }
                ?: tr(R.string.ui_auto_no_current),
            "auto-add-wifi",
            enabled =
                editable &&
                    current != null &&
                    current !in draft.trustedWifi &&
                    draft.trustedWifi.size < MAX_TRUSTED_WIFI,
        ) {
            current?.let { s.change(draft.copy(trustedWifi = draft.trustedWifi + it)) }
        }
    }
}

@Composable
private fun TrustedNetworkRow(name: String, editable: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trusted-wifi"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onRemove, enabled = editable) {
            Text(tr(R.string.ui_auto_remove), color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun openSystem(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestTile(context: Context) {
    runCatching {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, ConnectTileService::class.java),
            "PingClash",
            Icon.createWithResource(context, R.drawable.ic_status),
            context.mainExecutor,
        ) {}
    }
}

private val LOCATION_PERMISSIONS =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private const val HOURS_PER_DAY = 24
private const val HOURS_PER_WEEK = 168
