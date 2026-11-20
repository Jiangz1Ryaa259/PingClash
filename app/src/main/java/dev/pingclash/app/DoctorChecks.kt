// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Fixed thresholds of the doctor's local checks.

package dev.pingclash.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.net.Inet6Address

/**
 * The doctor's checks that need nothing outside the phone: the plan the subscription reported, the
 * clock against the network's time, and the phone's network and settings. None of it is sent
 * anywhere; it is shown on the page and in the report the user chooses to copy.
 */
internal data class PlanCheck(
    /** Bytes left, or null when the subscription does not say. */
    val remaining: Long?,
    /** Expiry in epoch seconds, or null when it does not expire or does not say. */
    val expire: Long?,
    val now: Long,
) {
    val expired
        get() = expire != null && expire in 1 until now

    val usedUp
        get() = remaining != null && remaining <= 0

    val known
        get() = remaining != null || expire != null
}

/** How far the phone's clock is from the network's time; null skew when it cannot be known. */
internal data class ClockCheck(val skewSeconds: Long?, val automatic: Boolean) {
    val off
        get() = skewSeconds != null && kotlin.math.abs(skewSeconds) > CLOCK_LIMIT_SECONDS
}

/** VMess and certificate checks refuse a clock further off than about this. */
internal const val CLOCK_LIMIT_SECONDS = 90L

/** The kind of network the phone uses outside the VPN. */
internal enum class LinkKind {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    NONE,
}

/** The phone's network and the settings that can quietly stop a VPN from working. */
internal data class PhoneCheck(
    val link: LinkKind,
    /** Android confirmed this network reaches the internet. */
    val validated: Boolean,
    /** The network wants a sign-in page first (hotel or café Wi-Fi). */
    val captive: Boolean,
    /** "off", "opportunistic" (automatic) or "hostname" (a fixed provider). */
    val privateDns: String,
    val dataSaver: Boolean,
    val batteryRestricted: Boolean,
    val systemProxy: Boolean,
    val ipv6: Boolean,
    val android: Int,
    val maker: String,
) {
    val offline
        get() = link == LinkKind.NONE
}

internal fun planCheck(profile: LocalProfile?): PlanCheck {
    val quota = profile?.quota
    val now = System.currentTimeMillis() / 1000
    val remaining = quota?.total?.takeIf { it > 0 }?.let { total -> quota.used?.let { total - it } }
    return PlanCheck(remaining, quota?.expire?.takeIf { it > 0 }, now)
}

/**
 * The clock against the time the mobile network or the system's time sync reports (Android 13 and
 * later); no request is made for it. Earlier versions only say whether the time is set
 * automatically.
 */
internal fun clockCheck(context: Context): ClockCheck {
    val automatic =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME) }
            .getOrDefault(1) == 1
    val skew =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            runCatching {
                    val network = SystemClock.currentNetworkTimeClock().millis()
                    (System.currentTimeMillis() - network) / 1000
                }
                .getOrNull()
        else null
    return ClockCheck(skew, automatic)
}

internal fun phoneCheck(context: Context): PhoneCheck {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    // The network the core itself uses, not the VPN: the VPN's bound network while it is on,
    // otherwise the system's default one. Never just any network that happens to be up.
    val physical = physicalNetwork(context)
    val caps = physical?.let { manager?.getNetworkCapabilities(it) }
    val link = physical?.let { manager?.getLinkProperties(it) }
    val kind =
        when {
            caps == null -> LinkKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkKind.ETHERNET
            else -> LinkKind.OTHER
        }
    return PhoneCheck(
        link = kind,
        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        privateDns =
            runCatching { Settings.Global.getString(context.contentResolver, "private_dns_mode") }
                .getOrNull() ?: "opportunistic",
        dataSaver =
            manager?.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        // Only a restriction the user set on this app counts; standard optimisation, which nearly
        // every app has, does not stop a foreground VPN.
        batteryRestricted =
            context
                .getSystemService(android.app.ActivityManager::class.java)
                ?.isBackgroundRestricted == true,
        systemProxy = link?.httpProxy != null,
        ipv6 =
            link?.linkAddresses?.any {
                val address = it.address
                address is Inet6Address && !address.isLinkLocalAddress && !address.isLoopbackAddress
            } == true,
        android = Build.VERSION.SDK_INT,
        maker = Build.MANUFACTURER.orEmpty().take(24),
    )
}

internal fun linkLabel(link: LinkKind): String =
    tr(
        when (link) {
            LinkKind.WIFI -> R.string.doctor_link_wifi
            LinkKind.CELLULAR -> R.string.doctor_link_cellular
            LinkKind.ETHERNET -> R.string.doctor_link_ethernet
            LinkKind.OTHER -> R.string.doctor_link_other
            LinkKind.NONE -> R.string.doctor_link_none
        }
    )

/** The facts a support team asks first, none of which identify the user or their servers. */
internal data class SupportFacts(
    val app: String,
    val core: String,
    val time: String,
    val android: String,
    val network: String,
    val mode: String,
    val protocol: String,
)
