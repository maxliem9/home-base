package com.homebase.android.data.abwesenheit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the planner's person filter (null = everyone) — the Android analog of the web's
 * `localStorage['homebase_absence_person_filter']`. Same shape as `ShoppingViewPrefs`: a private
 * SharedPreferences file, disk I/O on [Dispatchers.IO].
 */
class AbsenceViewPrefs(context: Context) {
    private val appContext = context.applicationContext

    private val prefs get() = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    suspend fun loadPersonFilter(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY, null)
    }

    suspend fun savePersonFilter(userId: String?) = withContext(Dispatchers.IO) {
        prefs.edit().apply { if (userId == null) remove(KEY) else putString(KEY, userId) }.apply()
    }

    private companion object {
        const val FILE = "homebase_absence_view"
        const val KEY = "person_filter"
    }
}
