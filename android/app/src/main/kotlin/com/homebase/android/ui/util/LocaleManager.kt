package com.homebase.android.ui.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * In-app language switching (Issue #6). HomeBase ships German as its base locale and English
 * as a translation; users flip between them in Einstellungen → Sprache.
 *
 * Uses AndroidX AppCompat's per-app locales API, which backports
 * [AppCompatDelegate.setApplicationLocales] all the way to minSdk 26 — so it works even though
 * the host is a ComponentActivity, not an AppCompatActivity. Setting the locale recreates the
 * activity so the change applies immediately, and the choice is persisted across restarts by
 * AppCompat's AppLocalesMetadataHolderService (autoStoreLocales=true in the manifest).
 *
 * Keep the tags in sync with res/xml/locales_config.xml.
 */
object LocaleManager {

    /** Supported UI languages, in switcher order. */
    enum class Language(val tag: String) {
        GERMAN("de"),
        ENGLISH("en"),
    }

    /**
     * The language currently in effect, derived from the persisted application locales.
     * Falls back to German (the base locale) when nothing is set yet or the stored tag is
     * neither of the supported ones.
     */
    fun current(): Language {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tag.startsWith("en", ignoreCase = true) -> Language.ENGLISH
            else -> Language.GERMAN
        }
    }

    /** Apply [language] app-wide; recreates the activity so it takes effect immediately. */
    fun set(language: Language) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
