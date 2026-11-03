// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Only explicitly picked documents enter this reader; archives are never extracted. */
internal class ProfileDocument(
    val content: String,
    val localBodies: Map<String, String> = emptyMap(),
) {
    override fun toString() = "ProfileDocument(redacted)"

    companion object {
        private const val ENTRIES = 160
        private const val CONFIG_BYTES = MAX_FEED_BYTES
        private const val PATH_LENGTH = 512
        private const val SIGNATURE_BYTES = 4
        private val zipSignature = byteArrayOf(0x50, 0x4b, 3, 4)

        fun read(stream: InputStream): ProfileDocument {
            val input = BufferedInputStream(stream)
            input.mark(SIGNATURE_BYTES)
            val signature = ByteArray(SIGNATURE_BYTES)
            val count = input.read(signature)
            input.reset()
            return if (count == SIGNATURE_BYTES && signature.contentEquals(zipSignature))
                readBundle(input)
            else ProfileDocument(utf8(input.readBytesLimited(CONFIG_BYTES)))
        }

        private fun readBundle(input: InputStream): ProfileDocument {
            val files = readFiles(input)
            val configs = configNames.filter { it in files }
            require(configs.size == 1)
            val text = utf8(files.getValue(configs.single()))
            return ProfileDocument(text, readProviders(text, files))
        }

        private fun readFiles(input: InputStream): Map<String, ByteArray> {
            val files = linkedMapOf<String, ByteArray>()
            var total = 0L
            var entries = 0
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    check(!Thread.currentThread().isInterrupted)
                    require(++entries <= ENTRIES)
                    val body = readEntry(zip, entry, total)
                    if (body != null) {
                        require(body.first !in files)
                        files[body.first] = body.second
                        total += body.second.size
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return files
        }

        private fun readEntry(
            zip: ZipInputStream,
            entry: java.util.zip.ZipEntry,
            total: Long,
        ): Pair<String, ByteArray>? {
            if (entry.isDirectory) {
                require(zip.readBytesLimited(0).isEmpty())
                return null
            }
            val name = bundlePath(entry.name)
            val limit = if (name in configNames) CONFIG_BYTES else ProviderBodyCache.RULE_BYTES
            require(entry.size <= limit)
            val remaining = (ProviderBodyCache.TOTAL_BYTES + CONFIG_BYTES - total).toInt()
            val body = zip.readBytesLimited(minOf(limit, remaining))
            return name to body
        }

        private fun readProviders(
            text: String,
            files: Map<String, ByteArray>,
        ): Map<String, String> {
            val bodies = mutableMapOf<String, String>()
            val specs = RemoteProfiles.plan(text)
            for (i in 0 until specs.length()) {
                val spec = specs.getJSONObject(i)
                if (spec.optString("mode") != "file") continue
                val path = bundlePath(spec.getString("path"))
                val bytes = files[path] ?: throw providerLocalFileFailure(spec)
                val maximum =
                    if (spec.getString("kind") == "rule") ProviderBodyCache.RULE_BYTES
                    else CONFIG_BYTES
                require(bytes.size <= maximum)
                val declared = spec.optLong("sizeLimit")
                if (declared > 0 && bytes.size > declared)
                    throw ProviderFailure(
                        spec.getInt("index"),
                        spec.getString("kind"),
                        "provider_declared_size_limit",
                    )
                bodies[spec.getString("id")] =
                    if (spec.optString("format") == "mrs") ProviderBodyCache.packBinary(bytes)
                    else ProviderBodyCache.pack(utf8(bytes))
            }
            return bodies
        }

        private val configNames = listOf("config.yaml", "config.yml", "config.json")

        private fun utf8(bytes: ByteArray): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()

        internal fun bundlePath(raw: String): String {
            require(
                raw.isNotEmpty() &&
                    raw.length <= PATH_LENGTH &&
                    raw.none { it.isISOControl() } &&
                    !raw.startsWith('/') &&
                    '\\' !in raw &&
                    ':' !in raw
            )
            val parts = raw.split('/')
            require(parts.none { it == ".." || it.isEmpty() })
            return parts.filter { it != "." }.joinToString("/").also { require(it.isNotEmpty()) }
        }
    }
}

internal fun providerLocalFileFailure(spec: org.json.JSONObject) =
    ProviderFailure(spec.getInt("index"), spec.getString("kind"), "provider_local_file_required")
