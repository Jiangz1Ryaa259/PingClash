// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Approximate province centres: fixed geographic data.

package dev.pingclash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the phone is, for the error details: the mobile carrier and country from the SIM and
 * network (no permission), and, only if location is already allowed, the province, worked out on
 * the phone from a small table of province centres. The app never asks for location here, asks no
 * map or geocoding service, and sends nothing.
 */
internal data class RegionInfo(
    val country: String?,
    val carrier: String?,
    val province: Province?,
    val located: Boolean,
)

/** A province (or region) of China with the points it is nearest to. */
internal enum class Province(val label: Int, vararg val points: Pair<Double, Double>) {
    BEIJING(R.string.province_beijing, 40.2 to 116.4),
    TIANJIN(R.string.province_tianjin, 39.3 to 117.3),
    HEBEI(R.string.province_hebei, 38.5 to 115.3, 41.0 to 116.0),
    SHANXI(R.string.province_shanxi, 37.6 to 112.3),
    INNER_MONGOLIA(R.string.province_inner_mongolia, 43.5 to 114.5, 41.0 to 108.0, 48.0 to 121.0),
    LIAONING(R.string.province_liaoning, 41.3 to 122.6),
    JILIN(R.string.province_jilin, 43.7 to 126.2),
    HEILONGJIANG(R.string.province_heilongjiang, 47.9 to 128.0),
    SHANGHAI(R.string.province_shanghai, 31.2 to 121.5),
    JIANGSU(R.string.province_jiangsu, 32.9 to 119.5),
    ZHEJIANG(R.string.province_zhejiang, 29.2 to 120.1),
    ANHUI(R.string.province_anhui, 31.8 to 117.2),
    FUJIAN(R.string.province_fujian, 26.1 to 118.2),
    JIANGXI(R.string.province_jiangxi, 27.6 to 115.7),
    SHANDONG(R.string.province_shandong, 36.4 to 118.5),
    HENAN(R.string.province_henan, 33.9 to 113.6),
    HUBEI(R.string.province_hubei, 30.9 to 112.3),
    HUNAN(R.string.province_hunan, 27.6 to 111.7),
    GUANGDONG(R.string.province_guangdong, 23.4 to 113.4),
    GUANGXI(R.string.province_guangxi, 23.7 to 108.8),
    HAINAN(R.string.province_hainan, 19.2 to 109.7),
    CHONGQING(R.string.province_chongqing, 30.0 to 107.9),
    SICHUAN(R.string.province_sichuan, 30.6 to 104.1, 30.6 to 101.0),
    GUIZHOU(R.string.province_guizhou, 26.8 to 106.8),
    YUNNAN(R.string.province_yunnan, 24.9 to 101.5),
    TIBET(R.string.province_tibet, 29.6 to 91.1, 31.5 to 86.0),
    SHAANXI(R.string.province_shaanxi, 35.2 to 108.9),
    GANSU(R.string.province_gansu, 36.1 to 103.8, 38.9 to 100.4, 40.5 to 96.0),
    QINGHAI(R.string.province_qinghai, 35.7 to 96.0),
    NINGXIA(R.string.province_ningxia, 37.3 to 106.2),
    XINJIANG(R.string.province_xinjiang, 43.8 to 87.6, 41.1 to 85.3, 37.5 to 80.0),
    HONG_KONG(R.string.province_hong_kong, 22.32 to 114.17),
    MACAU(R.string.province_macau, 22.19 to 113.54),
    TAIWAN(R.string.province_taiwan, 23.7 to 121.0),
}

/** The nearest province to a point in or around China; null well outside it. */
internal fun nearestProvince(latitude: Double, longitude: Double): Province? {
    if (latitude !in 17.0..54.5 || longitude !in 73.0..135.5) return null
    val scale = cos(Math.toRadians(latitude))
    return Province.entries.minByOrNull { province ->
        province.points.minOf { (lat, lon) ->
            val dy = lat - latitude
            val dx = (lon - longitude) * scale
            dy * dy + dx * dx
        }
    }
}

internal fun locationAllowed(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The carrier and country, and the province when location is allowed and known. Binder calls and a
 * short wait for a fix: call it off the main thread.
 */
internal suspend fun regionInfo(context: Context): RegionInfo {
    val phone = context.getSystemService(TelephonyManager::class.java)
    val country =
        (phone?.networkCountryIso?.ifBlank { null } ?: phone?.simCountryIso?.ifBlank { null })
            ?.uppercase()
    val carrier = phone?.networkOperatorName?.ifBlank { null }?.take(40)
    val place = if (locationAllowed(context)) lastPlace(context) else null
    val province =
        place
            ?.let { nearestProvince(it.latitude, it.longitude) }
            ?.takeIf { country == null || country in setOf("CN", "HK", "MO", "TW") }
    return RegionInfo(country, carrier, province, place != null)
}

// Called only once locationAllowed() is true; each source that has nothing returns early.
@Suppress("MissingPermission", "ReturnCount")
private suspend fun lastPlace(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val known =
        runCatching {
                manager
                    .getProviders(true)
                    .mapNotNull { manager.getLastKnownLocation(it) }
                    .maxByOrNull { it.time }
            }
            .getOrNull()
    // A place from the last day is near enough for a province.
    if (known != null && System.currentTimeMillis() - known.time < 86_400_000L) return known
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return known
    // Only the network provider answers quickly from Wi-Fi and cell towers; the passive one only
    // waits for another app's fix, so a phone without network location is not kept waiting.
    val provider =
        LocationManager.NETWORK_PROVIDER.takeIf {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return known
    return withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine { done ->
            val signal = CancellationSignal()
            done.invokeOnCancellation { signal.cancel() }
            runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) {
                        if (done.isActive) done.resume(it)
                    }
                }
                .onFailure { if (done.isActive) done.resume(null) }
        }
    } ?: known
}
