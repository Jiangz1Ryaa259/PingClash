// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONArray
import org.json.JSONObject

/** A profile in a backup: a subscription by its link, or a local profile by its configuration. */
internal data class BackupProfile(
    val name: String,
    val subscription: String? = null,
    val userAgent: String = DEFAULT_SUBSCRIPTION_UA,
    val configuration: String? = null,
) {
    override fun toString() = "BackupProfile(redacted)"
}

internal data class SettingsBackupContent(
    val settings: ClientSettings,
    val dns: String,
    val probe: String,
    val profiles: List<BackupProfile> = emptyList(),
) {
    override fun toString() = "SettingsBackupContent(redacted)"
}

/**
 * The settings backup file. Without [withProfiles] it holds preferences only: no subscription
 * links, node passwords or usage. With it, subscription links (which usually carry an access token)
 * and local profiles' configurations are included, so the file must be kept private.
 */
internal object SettingsBackup {
    const val FORMAT = "pingclash-backup"
    private const val VERSION = 1
    private const val MAX_PROFILES = 200
    /** Room for many local profiles; each one is limited like an import. */
    const val MAX_BYTES = 4 * MAX_FEED_BYTES

    fun export(
        data: AppData,
        withProfiles: Boolean,
        configuration: (LocalProfile) -> String = ::configurationExport,
    ): String {
        val root =
            JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("settings", data.client.json())
                .put("dns", data.dnsAddress)
                .put("probe", data.probeUrl)
        if (withProfiles)
            root.put(
                "profiles",
                JSONArray(
                    data.profiles.take(MAX_PROFILES).mapNotNull { profile ->
                        val entry = JSONObject().put("name", profile.name)
                        when {
                            profile.autoUpdates ->
                                entry
                                    .put("subscription", profile.source)
                                    .put("userAgent", profile.userAgent)
                            else ->
                                runCatching { configuration(profile) }
                                    .getOrNull()
                                    ?.let { entry.put("configuration", it) }
                        }
                    }
                ),
            )
        return root.toString(2)
    }

    /** The file's text, refusing anything larger than a backup can be. */
    fun readFile(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BYTES)
                throw SettingsFailure(tr(R.string.ui_backup_invalid))
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Reads and checks a backup; throws [SettingsFailure] when it is not a usable one. */
    fun read(text: String): SettingsBackupContent {
        fun invalid(): Nothing = throw SettingsFailure(tr(R.string.ui_backup_invalid))
        val root = runCatching { JSONObject(text) }.getOrNull() ?: invalid()
        if (root.optString("format") != FORMAT || root.optInt("version") !in 1..VERSION) invalid()
        val settings =
            runCatching { ClientSettings.read(root.getJSONObject("settings")) }.getOrNull()
                ?: invalid()
        runCatching { validateClientSettings(settings) }.onFailure { invalid() }
        val list = root.optJSONArray("profiles") ?: JSONArray()
        if (list.length() > MAX_PROFILES) invalid()
        val profiles =
            (0 until list.length()).mapNotNull { index ->
                val entry = list.optJSONObject(index) ?: return@mapNotNull null
                val name = entry.optString("name").trim().take(MAX_NAME)
                val link = entry.optString("subscription").takeIf(::validHttps)
                val config = entry.optString("configuration").takeIf { it.isNotBlank() }
                when {
                    name.isBlank() -> null
                    link != null ->
                        BackupProfile(
                            name,
                            subscription = link,
                            userAgent =
                                entry.optString("userAgent").ifBlank { DEFAULT_SUBSCRIPTION_UA },
                        )
                    config != null && config.length <= MAX_FEED_BYTES ->
                        BackupProfile(name, configuration = config)
                    else -> null
                }
            }
        return SettingsBackupContent(
            settings,
            root.optString("dns").ifBlank { DEFAULT_DNS_ADDRESS },
            root.optString("probe").ifBlank { AppData().probeUrl },
            profiles,
        )
    }

    private const val MAX_NAME = 100
    private const val READ_BUFFER = 64 * 1024
}
