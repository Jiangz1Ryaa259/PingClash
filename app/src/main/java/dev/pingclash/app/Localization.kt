// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

// The composition key also invalidates pre-Android-13 resource consumers when
// the encrypted in-app language preference changes.
internal val LocalUiLanguage = staticCompositionLocalOf { "system" }

/** A language the app speaks: its code, its locale tag, and its name written in itself. */
internal class UiLanguage(val code: String, val tag: String, val name: String)

/**
 * Every translation. A new language needs an entry here, its values-xx folder and locale config.
 */
internal val UI_LANGUAGES =
    listOf(
        UiLanguage("zh", "zh-CN", "简体中文"),
        UiLanguage("en", "en", "English"),
        UiLanguage("ja", "ja", "日本語"),
    )

internal fun uiLanguageSupported(code: String) = UI_LANGUAGES.any { it.code == code }

private fun uiLanguageTag(code: String) = UI_LANGUAGES.firstOrNull { it.code == code }?.tag

internal object UiStrings {
    private lateinit var app: Application
    private var legacyLanguage by mutableStateOf("system")
    @Volatile private var cached: Pair<String, Resources>? = null

    fun initialize(application: Application) {
        app = application
    }

    fun migrate(language: String) {
        legacyLanguage = language
        if (Build.VERSION.SDK_INT >= 33) {
            val flags = app.getSharedPreferences("ui_locales", 0)
            if (!flags.getBoolean("migrated", false)) {
                val manager = app.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty && language != "system") apply(language)
                flags.edit().putBoolean("migrated", true).apply()
            }
        }
    }

    fun preference(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java)
                .applicationLocales
                .get(0)
                ?.language
                ?.takeIf(::uiLanguageSupported) ?: "system"
        } else legacyLanguage

    fun apply(language: String) {
        require(language == "system" || uiLanguageSupported(language))
        legacyLanguage = language
        cached = null
        if (Build.VERSION.SDK_INT >= 33)
            app.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(uiLanguageTag(language).orEmpty())
    }

    fun resources(): Resources {
        val base = app.resources
        val key =
            (if (Build.VERSION.SDK_INT >= 33) "android" else legacyLanguage) +
                ":" +
                base.configuration.hashCode()
        cached
            ?.takeIf { it.first == key }
            ?.let {
                return it.second
            }
        val language = if (Build.VERSION.SDK_INT >= 33) preference() else legacyLanguage
        val value =
            if (language == "system") base
            else
                app.createConfigurationContext(
                        Configuration(base.configuration).apply {
                            setLocales(
                                LocaleList(Locale.forLanguageTag(uiLanguageTag(language) ?: "en"))
                            )
                        }
                    )
                    .resources
        cached = key to value
        return value
    }
}

internal fun tr(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) UiStrings.resources().getString(id)
    else UiStrings.resources().getString(id, *args)

internal fun trPlural(@PluralsRes id: Int, quantity: Int, vararg args: Any?): String =
    UiStrings.resources().getQuantityString(id, quantity, *args)
