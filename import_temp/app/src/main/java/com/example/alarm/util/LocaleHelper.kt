package com.example.alarm.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.os.LocaleListCompat
import androidx.core.app.LocaleManagerCompat
import java.util.Locale

/**
 * Single source of truth for the app's UI language — no external dependency required
 * (uses only framework APIs + androidx.core, which is already on the classpath).
 *
 * IMPORTANT: only "en" and "hi" have full translation dictionaries (see AlarmViewModel).
 * Selecting any other device language applies that LOCALE (date/number formatting, RTL,
 * and any future string resources) but UI text falls back to English. This is the
 * unavoidable consequence of having no translation library / no on-device ML translation.
 */
object LocaleHelper {

    private const val PREFS = "sun_alarm_settings_prefs"
    private const val KEY_LANG = "app_language"

    data class LangOption(val tag: String, val displayName: String)

    /** Reads the persisted BCP-47 language tag (defaults to "en"). */
    fun getPersistedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "en") ?: "en"

    /** Persists the tag and, on API 33+, informs the framework LocaleManager. */
    fun persist(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, tag).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(tag)
        }
    }

    /** Wraps a base context so its Configuration carries the saved locale. */
    fun wrap(base: Context): Context {
        val tag = getPersistedTag(base)
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Builds the dropdown list from the DEVICE's configured languages.
     * De-duped by primary language code; "en" and "hi" are always present first.
     * Each shown in its own script via Locale.getDisplayName(self).
     */
    fun availableLanguages(context: Context): List<LangOption> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<LangOption>()

        fun add(locale: Locale) {
            val lang = locale.language.lowercase()
            if (lang.isNotEmpty() && seen.add(lang)) {
                val name = locale.getDisplayName(locale)
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
                out += LangOption(locale.toLanguageTag(), name)
            }
        }

        // Guarantee English + Hindi (the fully-translated languages) first.
        add(Locale.ENGLISH)
        add(Locale("hi"))

        // Then every language the user has added to their phone.
        val sys: LocaleListCompat = LocaleManagerCompat.getSystemLocales(context)
        for (i in 0 until sys.size()) {
            sys.get(i)?.let { add(it) }
        }

        // Also pull from the current Configuration's LocaleList, which on many
        // devices carries the full set of languages the user has configured even
        // when getSystemLocales() reports only the single active locale.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val configLocales: LocaleList = context.resources.configuration.locales
            for (i in 0 until configLocales.size()) {
                configLocales.get(i)?.let { add(it) }
            }
        }
        return out
    }
}
