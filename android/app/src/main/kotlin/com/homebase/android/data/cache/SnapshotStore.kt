package com.homebase.android.data.cache

import android.content.Context
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable, single-value "last-known data" cache for a screen (issue #517).
 *
 * A screen fetches its data from the backend and mirrors the latest successful result here. On a
 * cold start with no connection — the classic "opened the app in the shop, no signal, everything is
 * gone" case — the ViewModel seeds its state from this cache so the user sees the *previous* state
 * instead of an empty screen, and only replaces it once a fetch succeeds. This is the read-side
 * analog of the offline write queues (`ShoppingPendingStore`, `NotesPendingStore`).
 *
 * Deliberately generic so every view can get the same behavior with a one-line wiring in
 * `AppContainer` (pass a Moshi adapter for the view's snapshot type + a private prefs file name).
 * The `suspend` surface keeps the disk I/O off the main thread and the interface trivially fakeable
 * in unit tests.
 */
interface SnapshotStore<T> {
    /** The last persisted snapshot, or null if none was ever written / the blob is unreadable. */
    suspend fun load(): T?

    /** Overwrite the persisted snapshot with [snapshot] (best-effort; a failed write is swallowed). */
    suspend fun save(snapshot: T)
}

/**
 * [SnapshotStore] backed by a private [android.content.SharedPreferences] file holding the snapshot
 * as one Moshi-serialized JSON blob — the read-side twin of `SharedPrefsShoppingPendingStore`.
 *
 * Both accessors hop to [Dispatchers.IO]: the first `getSharedPreferences` does blocking disk I/O
 * and `apply()` still touches the in-memory map synchronously, so neither belongs on the main thread
 * (ANR / StrictMode). Writes use `apply()` rather than `commit()`: this is a best-effort cache, not a
 * durability guarantee (that is the pending queue's job), so losing the very last write on a hard
 * kill is acceptable and the async flush coalesces the frequent writes a live-editing screen makes.
 *
 * A corrupt or unreadable value returns null (start clean) rather than crashing — the same defensive
 * stance the pending stores and the web `localStorage` readers take: a lost cache is bad, a boot loop
 * is worse.
 */
class SharedPrefsSnapshotStore<T>(
    context: Context,
    private val adapter: JsonAdapter<T>,
    private val prefsName: String,
) : SnapshotStore<T> {

    private val appContext = context.applicationContext

    // Lazy so even the first getSharedPreferences (which touches disk) stays off the main thread —
    // it is only realized inside the withContext(IO) blocks below.
    private val prefs by lazy { appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    override suspend fun load(): T? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY, null) ?: return@withContext null
        runCatching { adapter.fromJson(raw) }.getOrNull()
    }

    override suspend fun save(snapshot: T) = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().putString(KEY, adapter.toJson(snapshot)).apply() }
        Unit
    }

    private companion object {
        const val KEY = "snapshot"
    }
}
