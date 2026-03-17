package com.swordfish.lemuroid.lib.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "lemuroid_locale_prefs"
    const val PREF_KEY = "app_language"
    const val VALUE_SYSTEM = "system"
    const val VALUE_EN = "en"
    const val VALUE_PT = "pt"

    val ALL_VALUES = listOf(VALUE_SYSTEM, VALUE_EN, VALUE_PT)

    fun getSharedPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLanguage(context: Context): String =
        getSharedPreferences(context).getString(PREF_KEY, VALUE_SYSTEM) ?: VALUE_SYSTEM

    fun setLanguage(context: Context, language: String) {
        getSharedPreferences(context).edit().putString(PREF_KEY, language).apply()
    }

    /**
     * Returns a context configured with the user-selected locale.
     * Returns the original context unchanged when the setting is "system".
     */
    fun wrapContext(context: Context): Context {
        val language = getLanguage(context)
        if (language == VALUE_SYSTEM) return context
        val locale = when (language) {
            VALUE_EN -> Locale.ENGLISH
            VALUE_PT -> Locale("pt", "BR")
            else -> return context
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
