package com.homebase.android.data.shopping

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter

/**
 * [ShoppingPendingStore] backed by a private [android.content.SharedPreferences] file holding the
 * queue as a single Moshi-serialized JSON object (item id → [PendingCheck]). The whole map is read
 * and rewritten on each access — fine for the handful of entries this queue ever holds.
 *
 * A corrupt or unreadable value starts clean rather than crashing (the same defensive stance the
 * web takes for a bad `localStorage` blob): a lost queue is bad, but a boot loop is worse.
 */
class SharedPrefsShoppingPendingStore(
    context: Context,
    moshi: Moshi,
) : ShoppingPendingStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @OptIn(ExperimentalStdlibApi::class)
    private val adapter = moshi.adapter<Map<String, PendingCheck>>(
        Types.newParameterizedType(Map::class.java, String::class.java, PendingCheck::class.java),
    )

    override fun load(): Map<String, PendingCheck> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching { adapter.fromJson(raw) }.getOrNull() ?: emptyMap()
    }

    override fun save(pending: Map<String, PendingCheck>) {
        prefs.edit().apply {
            if (pending.isEmpty()) remove(KEY) else putString(KEY, adapter.toJson(pending))
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "homebase_shopping_pending"
        const val KEY = "pending"
    }
}
