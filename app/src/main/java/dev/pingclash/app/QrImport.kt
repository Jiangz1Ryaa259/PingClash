// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Pure local QR decoder. No network requests, analytics, persisted images or native code. */
internal object QrImport {
    private const val IMAGE_BYTES = 16 * 1024 * 1024
    private const val EDGE = 2048

    @Suppress("ReturnCount") // Reject resource and format bounds before allocation.
    fun decode(resolver: ContentResolver, uri: Uri): String? {
        require(uri.scheme == "content")
        val bytes =
            resolver.openInputStream(uri)?.use { it.readBytesLimited(IMAGE_BYTES) } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > EDGE || bounds.outHeight / sample > EDGE) sample *= 2
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("ReturnCount") // Bounded decoding attempts, first valid QR wins.
    fun decode(bitmap: Bitmap): String? {
        if (bitmap.width > EDGE || bitmap.height > EDGE || bitmap.isRecycled) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints =
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        for (luminance in listOf(source, source.invert())) {
            val result =
                runCatching {
                        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)), hints).text
                    }
                    .getOrNull()
            if (!result.isNullOrBlank() && result.length <= IMPORT_TEXT_LIMIT) return result
        }
        return null
    }
}
