// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** How much data is left decides the colour, as a phone's battery does. */
internal enum class QuotaLevel {
    GOOD,
    LOW,
    CRITICAL,
}

internal const val QUOTA_LOW = .40f
internal const val QUOTA_CRITICAL = .20f

internal fun quotaLevel(remaining: Float): QuotaLevel =
    when {
        remaining < QUOTA_CRITICAL -> QuotaLevel.CRITICAL
        remaining < QUOTA_LOW -> QuotaLevel.LOW
        else -> QuotaLevel.GOOD
    }

internal fun quotaLevelColor(level: QuotaLevel, dark: Boolean): Color =
    when (level) {
        QuotaLevel.GOOD -> latencyGood(dark)
        QuotaLevel.LOW -> latencySlow(dark)
        QuotaLevel.CRITICAL -> latencyBad(dark)
    }

private const val KIB = 1024.0
private val DATA_UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * The data left of the total, in the total's unit with up to two decimals: "144.25 / 200 GB". Null
 * when the subscription does not say both.
 */
internal fun quotaAllowance(quota: SubscriptionQuota?): String? {
    val total = quota?.total?.takeIf { it > 0 }
    val left = quota?.remaining
    if (total == null || left == null) return null
    var size = 1.0
    var unit = 0
    while (total / size >= KIB && unit < DATA_UNITS.lastIndex) {
        size *= KIB
        unit++
    }
    return "${decimals(left / size)} / ${decimals(total / size)} ${DATA_UNITS[unit]}"
}

private fun decimals(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

/** The phone's sideways lean, from -1 to 1; a plain value read once a frame, never stored. */
internal class Lean {
    @Volatile var value = 0f
}

private const val GRAVITY = 9.81f
private const val LEAN_GAIN = 1.6f

/**
 * Follows the accelerometer while [enabled] and composed, and not otherwise. It needs no
 * permission; only the sideways component is kept, and only in memory.
 */
@Composable
internal fun rememberLean(enabled: Boolean): Lean {
    val lean = remember { Lean() }
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    lean.value = (event.values[0] / GRAVITY * LEAN_GAIN).coerceIn(-1f, 1f)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
        if (enabled && sensor != null)
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            manager?.unregisterListener(listener)
            lean.value = 0f
        }
    }
    return lean
}
