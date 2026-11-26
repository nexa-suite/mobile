package com.nexa.mobile.operations

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

internal enum class DisplayLanguage(val tag: String) {
    System("system"),
    English("en-US"),
    Spanish("es-419");

    companion object {
        fun fromTag(tag: String?): DisplayLanguage =
            entries.firstOrNull { it.tag == tag } ?: English
    }
}

internal object DisplayLanguageSettings {
    private const val PREFERENCES = "display_language"
    private const val KEY = "locale_tag"

    fun selected(context: Context): DisplayLanguage = DisplayLanguage.fromTag(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
    )

    fun apply(context: Context): Context {
        val language = selected(context)
        if (language == DisplayLanguage.System) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
        return context.createConfigurationContext(configuration)
    }

    fun select(activity: Activity, language: DisplayLanguage) {
        if (selected(activity) == language) return
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY, language.tag)
        }
        activity.recreate()
    }
}
