package com.homebase.android.data.notes

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [NotesPendingStore] backed by a private [android.content.SharedPreferences] file holding the queue
 * as a single Moshi-serialized JSON object (note id / [NEW_KEY] → [PendingNote]). The whole map is
 * read and rewritten on each access — fine for the at-most-one open draft this queue ever holds.
 *
 * Both accessors hop to [Dispatchers.IO]: the first `getSharedPreferences` read does blocking disk
 * I/O and `commit()` writes synchronously, so neither may run on the main thread — mirroring
 * [com.homebase.android.data.shopping.SharedPrefsShoppingPendingStore] and the project's
 * `AuthRepository` pattern.
 *
 * A corrupt or unreadable value starts clean rather than crashing (the same defensive stance the web
 * takes for a bad `localStorage` blob): a lost queue is bad, but a boot loop is worse.
 */
class SharedPrefsNotesPendingStore(
    context: Context,
    moshi: Moshi,
) : NotesPendingStore {

    private val appContext = context.applicationContext

    // Lazy so even the first getSharedPreferences disk touch stays off the main thread — only
    // realized inside the withContext(IO) blocks below.
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    @OptIn(ExperimentalStdlibApi::class)
    private val adapter = moshi.adapter<Map<String, PendingNote>>(
        Types.newParameterizedType(Map::class.java, String::class.java, PendingNote::class.java),
    )

    override suspend fun load(): Map<String, PendingNote> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY, null) ?: return@withContext emptyMap()
        runCatching { adapter.fromJson(raw) }.getOrNull() ?: emptyMap()
    }

    override suspend fun save(pending: Map<String, PendingNote>) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            if (pending.isEmpty()) remove(KEY) else putString(KEY, adapter.toJson(pending))
        }.commit()
        Unit
    }

    private companion object {
        const val PREFS_NAME = "homebase_notes_pending"
        const val KEY = "pending"
    }
}
