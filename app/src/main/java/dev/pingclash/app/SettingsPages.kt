// SPDX-License-Identifier: GPL-3.0-only
// Ranges, steps and length limits are the settings' documented bounds.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun DnsPage(s: SettingsDraft, dns: String, onDns: (String) -> Unit) {
    val draft = s.value
    SettingsSection {
        SettingSwitch(tr(R.string.use_subscription_dns), draft.useSubscriptionDns, s.enabled) {
            s.change(draft.copy(useSubscriptionDns = it))
        }
    }
    SettingsSection {
        Choices(
            tr(R.string.mode),
            draft.dnsMode,
            listOf("tcp" to "DNS over TCP", "https" to "DNS over HTTPS"),
            s.enabled,
        ) {
            s.change(draft.copy(dnsMode = it))
        }
        RowDivider(underIcons = false)
        TextFieldRow("VPN DNS", dns, s.enabled) { if (it.length <= 80) onDns(it) }
        if (draft.dnsMode == "https") {
            RowDivider(underIcons = false)
            TextFieldRow(tr(R.string.encrypted_dns_url), draft.dnsHttps, s.enabled) {
                if (it.length <= 2048) s.edit(draft.copy(dnsHttps = it))
            }
        }
    }
}

@Composable
internal fun NetworkPage(s: SettingsDraft, target: String, onTarget: (String) -> Unit) {
    val draft = s.value
    SettingsSection {
        SettingSwitch(tr(R.string.auto_reconnect), draft.autoReconnect, s.enabled) {
            s.change(draft.copy(autoReconnect = it))
        }
        RowDivider(underIcons = false)
        SettingSwitch(tr(R.string.enable_ipv6), draft.ipv6, s.enabled) {
            s.change(draft.copy(ipv6 = it))
        }
        RowDivider(underIcons = false)
        SettingSwitch(tr(R.string.enable_udp), draft.udp, s.enabled) {
            s.change(draft.copy(udp = it))
        }
    }
    SettingsSection(tr(R.string.ui_group_connection)) {
        NumberRow(NumberSpec("MTU", 1280..9000), draft.mtu, s.enabled, NumberInput.FIELD) {
            s.change(draft.copy(mtu = it))
        }
        RowDivider(underIcons = false)
        NumberRow(
            NumberSpec(tr(R.string.connect_timeout_seconds), 3..60),
            draft.connectTimeout,
            s.enabled,
            NumberInput.STEPPER,
        ) {
            s.change(draft.copy(connectTimeout = it))
        }
        RowDivider(underIcons = false)
        NumberRow(
            NumberSpec(tr(R.string.udp_idle_timeout_seconds), 10..300, 10),
            draft.udpTimeout,
            s.enabled,
            NumberInput.MENU,
        ) {
            s.change(draft.copy(udpTimeout = it))
        }
        RowDivider(underIcons = false)
        NumberRow(
            NumberSpec(tr(R.string.max_active_connections), 16..512, 16),
            draft.maxConnections,
            s.enabled,
            NumberInput.MENU,
        ) {
            s.change(draft.copy(maxConnections = it))
        }
    }
    TestingSection(s, target, onTarget)
}

@Composable
private fun TestingSection(s: SettingsDraft, target: String, onTarget: (String) -> Unit) {
    val draft = s.value
    SettingsSection(tr(R.string.ui_group_testing)) {
        TextFieldRow(tr(R.string.diagnostic_test_url), target, s.enabled) {
            if (it.length <= 2048) onTarget(it)
        }
        RowDivider(underIcons = false)
        TextFieldRow(tr(R.string.latency_target_empty_follows_group), draft.delayUrl, s.enabled) {
            if (it.length <= 2048) s.edit(draft.copy(delayUrl = it))
        }
        RowDivider(underIcons = false)
        NumberRow(
            NumberSpec(tr(R.string.test_timeout_milliseconds), 1000..20000, 1000),
            draft.probeTimeout,
            s.enabled,
            NumberInput.STEPPER,
        ) {
            s.change(draft.copy(probeTimeout = it))
        }
        RowDivider(underIcons = false)
        NumberRow(
            NumberSpec(tr(R.string.concurrent_tests), 1..16),
            draft.probeConcurrency,
            s.enabled,
            NumberInput.STEPPER,
        ) {
            s.change(draft.copy(probeConcurrency = it))
        }
        RowDivider(underIcons = false)
        SettingSwitch(tr(R.string.unified_delay), draft.unifiedDelay, s.enabled) {
            s.change(draft.copy(unifiedDelay = it))
        }
    }
}

@Composable
internal fun LanPage(s: SettingsDraft) {
    SettingsSection {
        Choices(
            tr(R.string.local_network),
            s.value.lan,
            listOf(
                "proxy" to tr(R.string.proxy_lan),
                "direct" to tr(R.string.direct_lan),
                "block" to tr(R.string.block_lan),
            ),
            s.enabled,
        ) {
            s.change(s.value.copy(lan = it))
        }
    }
}

@Composable
internal fun RulesPage(s: SettingsDraft, profile: LocalProfile?, onProviderRules: () -> Unit) {
    val draft = s.value
    SettingsSection { ModeSegments(draft.mode, s.enabled) { s.change(draft.copy(mode = it)) } }
    SettingsSection {
        SettingSwitch(tr(R.string.include_provider_rules), draft.useProviderRules, s.enabled) {
            s.change(draft.copy(useProviderRules = it))
        }
        RowDivider(underIcons = false)
        InfoRow(tr(R.string.profiles), profile?.name ?: "—")
        if (!profile?.rules.isNullOrEmpty()) {
            RowDivider(underIcons = false)
            NavRow(tr(R.string.view_provider_rules), "provider-rules", onClick = onProviderRules)
        }
    }
    SettingsSection(tr(R.string.custom_rules)) {
        TextAreaRow(
            draft.customRules,
            s.enabled,
            "custom-rules",
            "DOMAIN-SUFFIX,example.com,DIRECT",
        ) {
            if (it.toByteArray().size <= 64 * 1024) s.edit(draft.copy(customRules = it))
        }
    }
}

/** Settings > Background and notifications: system screens, the speed switch and reset. */
@Composable
internal fun ControlPage(s: SettingsDraft, notificationsEditable: Boolean, onReset: () -> Unit) {
    val context = LocalContext.current
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }
    SettingsSection {
        LinkRow(tr(R.string.system_vpn_settings), "link-vpn") {
            open(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        RowDivider(underIcons = false)
        LinkRow(tr(R.string.notification_settings), "link-notifications") {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
        RowDivider(underIcons = false)
        SettingSwitch(
            tr(R.string.show_speed_in_notification),
            s.value.notificationSpeed,
            notificationsEditable,
        ) {
            s.change(s.value.copy(notificationSpeed = it))
        }
        RowDivider(underIcons = false)
        LinkRow(tr(R.string.system_battery_settings), "link-battery") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
    SettingsSection {
        ActionRow(
            tr(R.string.restore_defaults),
            "restore-defaults",
            color = MaterialTheme.colorScheme.error,
            enabled = s.enabled,
            onClick = onReset,
        )
    }
}

@Composable
internal fun PrivacyPage(s: SettingsDraft, usageEditable: Boolean, onClearUsage: () -> Unit) {
    val draft = s.value
    SettingsSection {
        SettingSwitch(
            tr(R.string.show_destinations_session_memory_only),
            draft.inspectConnections,
            s.enabled,
        ) {
            s.change(draft.copy(inspectConnections = it))
        }
    }
    SettingsSection {
        SettingSwitch(
            tr(R.string.keep_aggregate_usage_on_this_device),
            draft.keepUsage,
            usageEditable,
        ) {
            s.change(draft.copy(keepUsage = it))
        }
        RowDivider(underIcons = false)
        ActionRow(
            tr(R.string.clear_usage_totals),
            "clear-usage",
            color = MaterialTheme.colorScheme.error,
            enabled = s.enabled,
            onClick = onClearUsage,
        )
    }
}
