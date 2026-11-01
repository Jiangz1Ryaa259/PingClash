// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Versioned private cache; compression is not encryption. Repository AES-GCM remains required. */
internal object ProviderBodyCache {
    const val RULE_BYTES = 16 * 1024 * 1024
    const val TOTAL_BYTES = 32 * 1024 * 1024
    const val CACHE_BYTES = 24 * 1024 * 1024
    private const val THRESHOLD = 64 * 1024
    private const val PREFIX = "pingclash-cache-v1:gzip:"
    private const val BINARY_PREFIX = "pingclash-mrs-v1:base64:"
    private const val BUFFER_BYTES = 8192

    fun pack(text: String): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (text.startsWith(BINARY_PREFIX) || raw.size < THRESHOLD) return text
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return PREFIX + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    fun decodedSize(text: String): Int {
        if (text.startsWith(BINARY_PREFIX)) {
            require(text.length <= CACHE_BYTES)
            return Base64.decode(text.removePrefix(BINARY_PREFIX), Base64.NO_WRAP).size.also {
                require(it <= RULE_BYTES)
            }
        }
        return if (!text.startsWith(PREFIX)) text.toByteArray(Charsets.UTF_8).size
        else gzipSize(text)
    }

    private fun gzipSize(text: String): Int {
        require(text.length <= CACHE_BYTES)
        val raw = Base64.decode(text.removePrefix(PREFIX), Base64.NO_WRAP)
        return GZIPInputStream(raw.inputStream()).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            var size = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= RULE_BYTES)
            }
            size
        }
    }

    fun packBinary(raw: ByteArray): String {
        require(raw.size <= RULE_BYTES)
        return BINARY_PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
