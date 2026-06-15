package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.UpdateUserPrefRequest
import com.homebase.android.ui.theme.ThemePref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * UI-theme preference store (#244) — the Android pendant of web's `useTheme` hook. Reads/writes the
 * per-user `theme` pref (`user_prefs`, values light|dark|system) via the generic /user-prefs
 * endpoints and exposes the current [ThemePref] as a [StateFlow] the app observes to recolour live.
 *
 * Resilience (mirrors web): the flow starts at [ThemePref.DEFAULT] (= system) so the UI renders
 * immediately and NEVER blocks on the network. [load] applies the stored choice once the GET lands;
 * if it fails or no pref is set, the default stands. [setTheme] applies optimistically (updates the
 * flow first) and reports whether the PUT persisted, so the picker can surface a save error without
 * reverting the user's choice.
 */
class ThemeRepository(private val api: HomeBaseApi) {

    private val _theme = MutableStateFlow(ThemePref.DEFAULT)

    /** The user's current theme choice (light/dark/system). Resolved to light/dark by the UI. */
    val theme: StateFlow<ThemePref> = _theme.asStateFlow()

    /**
     * Load the stored theme and apply it to [theme]. Best-effort: a failed/absent read leaves the
     * current value untouched (so the default — or a freshly-set choice — stands). Call once a token
     * is available (the endpoint is authenticated); cheap to re-call on reconnect.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching { api.getUserPrefs() }
            .onSuccess { prefs -> _theme.value = ThemePref.fromWire(prefs[ThemePref.PREF_KEY]) }
    }

    /**
     * Persist a new theme choice. Applies optimistically to [theme] first (instant UI feedback),
     * then PUTs it; returns whether the write succeeded. On failure the optimistic value stays (the
     * user sees their pick) — the caller may show a non-blocking "couldn't save" hint, matching web.
     */
    suspend fun setTheme(pref: ThemePref): Boolean = withContext(Dispatchers.IO) {
        _theme.value = pref
        runCatching {
            api.updateUserPref(ThemePref.PREF_KEY, UpdateUserPrefRequest(pref.wire))
        }.isSuccess
    }
}
