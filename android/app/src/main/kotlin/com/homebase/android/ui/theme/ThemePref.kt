package com.homebase.android.ui.theme

/**
 * UI theme choice (#244) — the Android pendant of web's `Theme` (web/src/ui/theme.ts). A per-user
 * preference stored in `user_prefs` under [PREF_KEY] (`light|dark|system`, default [SYSTEM]). The
 * stored value is the user's *choice*; `SYSTEM` is resolved to light/dark at render time via
 * `isSystemInDarkTheme()` (see MainActivity). Mirrors the web's coerce/resolve helpers so the two
 * clients agree on values and defaults.
 */
enum class ThemePref(val wire: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        /** The `user_prefs` key the theme is stored under (matches web THEME_PREF_KEY). */
        const val PREF_KEY = "theme"

        /** Default when nothing is stored or the read fails — follow the OS, like web. */
        val DEFAULT = SYSTEM

        /** Coerce an arbitrary stored string to a known choice (unknown/null → [DEFAULT]). */
        fun fromWire(value: String?): ThemePref =
            entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
