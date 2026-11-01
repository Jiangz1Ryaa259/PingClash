// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Panel-scoped memory only; no package inventory, disk cache or background observer. */
internal class AppIconLoader(
    context: Context,
    cacheEntries: Int = 64,
    private val decode: (String, Int) -> Bitmap? = { packageName, size ->
        rasterizeAppIcon(context.applicationContext.packageManager, packageName, size)
    },
) {
    private data class Key(val packageName: String, val size: Int)

    private data class Result(val bitmap: Bitmap?)

    // Size is capped as well as entry count, including failed/missing icons.
    private val cache = LruCache<Key, Result>(cacheEntries.coerceIn(1, 64))
    private val permits = Semaphore(4)

    /** Memory lookup only: cache hits do not flash the placeholder while awaiting a worker. */
    fun peek(packageName: String, requestedSize: Int): Bitmap? =
        cache.get(Key(packageName, requestedSize.coerceIn(1, 192)))?.bitmap

    suspend fun load(packageName: String, requestedSize: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            permits.withPermit {
                ensureActive()
                val key = Key(packageName, requestedSize.coerceIn(1, 192))
                val cached = cache.get(key)
                if (cached != null) return@withPermit cached.bitmap
                val bitmap = safelyDecode(key)
                // A recycled row must not publish or cache a late result.
                ensureActive()
                cache.put(key, Result(bitmap))
                bitmap
            }
        }

    private fun safelyDecode(key: Key): Bitmap? =
        try {
            decode(key.packageName, key.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            // Uninstall races and malformed package resources use the placeholder.
            null
        }
}

private fun rasterizeAppIcon(manager: PackageManager, packageName: String, size: Int): Bitmap =
    rasterizeRoutingIcon(manager.getApplicationIcon(packageName), size)

internal fun rasterizeRoutingIcon(source: Drawable, size: Int): Bitmap {
    val drawable = source.mutate()
    val raster = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
    val scale = minOf(size.toFloat() / width, size.toFloat() / height)
    val fittedWidth = (width * scale).roundToInt().coerceIn(1, size)
    val fittedHeight = (height * scale).roundToInt().coerceIn(1, size)
    val left = (size - fittedWidth) / 2
    val top = (size - fittedHeight) / 2
    drawable.setBounds(left, top, left + fittedWidth, top + fittedHeight)
    drawable.draw(Canvas(raster))
    // Only immutable pixels cross from the worker to Compose; no shared Drawable.
    return checkNotNull(raster.copy(Bitmap.Config.ARGB_8888, false))
}
