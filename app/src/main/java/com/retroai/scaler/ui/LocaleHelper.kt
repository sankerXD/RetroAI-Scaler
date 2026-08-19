package com.retroai.scaler.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Which language the UI is drawn in.
 *
 * [SYSTEM] is the default and needs no code at all: Android resolves
 * values-zh/ against the device locale and falls back to values/ (English) for
 * everything else. The other two exist only to OVERRIDE that, for the case the
 * device language and the language someone wants to read are not the same -
 * common enough on handhelds that ship with a locale nobody chose.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            values().firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * Applies the stored language by wrapping a base context.
 *
 * NOT AppCompatDelegate.setApplicationLocales, and the reason is the overlay.
 * That API drives Activity recreation; below API 33 it has nothing to say to a
 * Service, and the floating menu is inflated by [com.retroai.scaler.service.OverlayService],
 * not by an Activity. minSdk here is 30, so the two devices this is developed
 * against are exactly the ones it would not reach. Wrapping the base context
 * works the same on every version and covers both.
 *
 * Both MainActivity and OverlayService therefore override attachBaseContext and
 * route through [wrap]. Anything that inflates a layout or calls getString from
 * one of those contexts picks the language up; a context obtained some other
 * way (applicationContext) does not, which is why nothing user-facing should be
 * built from one.
 */
object LocaleHelper {
    private const val PREFS = "retroai_locale"
    private const val KEY_LANGUAGE = "language"

    fun stored(context: Context): AppLanguage =
        AppLanguage.fromTag(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null)
        )

    fun store(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }

    /**
     * Returns a context whose resources resolve in the chosen language, or the
     * one it was given when the choice is "follow the system".
     */
    fun wrap(base: Context): Context {
        val language = stored(base)
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Label for the picker, in the language it names rather than the current one. */
    fun labelOf(context: Context, language: AppLanguage): String = when (language) {
        AppLanguage.SYSTEM -> context.getString(com.retroai.scaler.R.string.language_system)
        AppLanguage.CHINESE -> context.getString(com.retroai.scaler.R.string.language_zh)
        AppLanguage.ENGLISH -> context.getString(com.retroai.scaler.R.string.language_en)
    }
}
