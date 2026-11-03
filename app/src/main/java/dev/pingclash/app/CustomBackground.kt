// SPDX-License-Identifier: GPL-3.0-only
// Format limits and buffer sizes are the documented import bounds.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableIntStateOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Why a picked image was not accepted; each maps to a fixed message. */
internal enum class BackgroundRejection {
    NOT_AN_IMAGE,
    TOO_LARGE,
    UNREADABLE,
}

internal class BackgroundImportException(val reason: BackgroundRejection) : IOException(reason.name)

/**
 * The user's own background picture. A picked image is untrusted input, so it is never kept or
 * re-read as picked: it is size-checked before decoding, decoded once by the platform decoder with
 * dimension limits, downscaled and re-encoded into private storage. The re-encode drops all
 * metadata (EXIF, including GPS location), and the app only ever loads its own copy afterwards.
 */
internal object CustomBackground {
    /** Bumped on every change so the backdrop reloads. */
    val revision = mutableIntStateOf(0)

    private const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    private const val MAX_SIDE = 12_000
    private const val MAX_PIXELS = 50_000_000L
    private const val TARGET_LONG_EDGE = 2400
    private const val QUALITY = 88
    private val TYPES =
        setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")

    fun file(context: Context) = File(context.noBackupFilesDir, "background/custom.webp")

    /** A picked picture waiting in the editor; it replaces [file] only on Done. */
    private fun pendingFile(context: Context) =
        File(context.noBackupFilesDir, "background/pending.webp")

    fun exists(context: Context) = file(context).isFile

    /**
     * Blocking; call off the main thread. Checks and re-encodes the picked image beside the current
     * picture, which stays in use, and returns its starting framing.
     */
    fun stage(context: Context, uri: Uri): BackgroundFrame {
        val type = context.contentResolver.getType(uri)
        if (type != null && type !in TYPES)
            throw BackgroundImportException(BackgroundRejection.NOT_AN_IMAGE)
        val bytes = readBounded(context, uri, MAX_INPUT_BYTES)
        val bitmap = decode(bytes)
        val edge: Int
        try {
            edge = BackgroundFrames.edgeColor(bitmap)
            store(bitmap, pendingFile(context), QUALITY)
        } finally {
            bitmap.recycle()
        }
        // A new picture starts filled and centred; its edge colour is offered for Fit, alongside
        // the colours the user added for earlier pictures.
        val custom = BackgroundFrames.load(context).custom
        return BackgroundFrame(fill = edge, auto = edge, custom = custom)
    }

    /** Blocking. The staged picture replaces the current one, framed by [frame]. */
    fun commit(context: Context, frame: BackgroundFrame) {
        if (!pendingFile(context).renameTo(file(context)))
            throw BackgroundImportException(BackgroundRejection.UNREADABLE)
        BackgroundFrames.save(context, frame)
    }

    /** Throws the staged picture away; the current background is untouched. */
    fun discard(context: Context) {
        pendingFile(context).delete()
    }

    fun clear(context: Context) {
        discard(context)
        file(context).delete()
        BackgroundFrames.clear(context)
        revision.intValue++
    }

    /** Loads the app's own re-encoded copy, or null when there is none. */
    fun load(context: Context, pending: Boolean = false): Bitmap? =
        (if (pending) pendingFile(context) else file(context))
            .takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    private fun decode(bytes: ByteArray): Bitmap =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                decoder,
                info,
                _ ->
                val width = info.size.width
                val height = info.size.height
                if (info.mimeType !in TYPES)
                    throw BackgroundImportException(BackgroundRejection.NOT_AN_IMAGE)
                // Checked from the header, before any pixel memory is allocated.
                val fits =
                    width in 1..MAX_SIDE &&
                        height in 1..MAX_SIDE &&
                        width.toLong() * height <= MAX_PIXELS
                if (!fits) throw BackgroundImportException(BackgroundRejection.TOO_LARGE)
                val scale = minOf(1f, TARGET_LONG_EDGE.toFloat() / maxOf(width, height))
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // A truncated or corrupt file is rejected instead of shown half-decoded.
                decoder.setOnPartialImageListener { false }
            }
        } catch (rejected: BackgroundImportException) {
            throw rejected
        } catch (_: Exception) {
            throw BackgroundImportException(BackgroundRejection.NOT_AN_IMAGE)
        }
}

/** Reads at most [limit] bytes of the picked file, rejecting anything larger. */
private fun readBounded(context: Context, uri: Uri, limit: Int): ByteArray {
    val input =
        context.contentResolver.openInputStream(uri)
            ?: throw BackgroundImportException(BackgroundRejection.UNREADABLE)
    return input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > limit)
                throw BackgroundImportException(BackgroundRejection.TOO_LARGE)
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }
}

/** Re-encodes [bitmap] as WebP into [target], atomically. */
private fun store(bitmap: Bitmap, target: File, quality: Int) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, target.name + ".tmp")
    @Suppress("DEPRECATION")
    val format =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
    temporary.outputStream().use {
        if (!bitmap.compress(format, quality, it))
            throw BackgroundImportException(BackgroundRejection.UNREADABLE)
    }
    if (!temporary.renameTo(target)) {
        temporary.delete()
        throw BackgroundImportException(BackgroundRejection.UNREADABLE)
    }
}
