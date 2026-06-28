package com.homebase.android.data.shopping

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tiny persistence for the shopping list/tile view choice (#446) — the Android analog of the web's
 * `localStorage` flag. Backed by a private SharedPreferences file; disk I/O hops to [Dispatchers.IO]
 * (the project's off-main convention, matching [SharedPrefsShoppingPendingStore]).
 */
class ShoppingViewPrefs(context: Context) {
    private val appContext = context.applicationContext

    private val prefs get() = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Tile view on? Defaults to true (tiles), matching the web default. */
    suspend fun loadTileView(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY, true)
    }

    suspend fun saveTileView(tiles: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY, tiles).apply()
    }

    private companion object {
        const val FILE = "homebase_shopping_view"
        const val KEY = "tile_view"
    }
}
