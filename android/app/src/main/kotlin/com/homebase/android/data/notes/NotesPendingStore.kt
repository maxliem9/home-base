package com.homebase.android.data.notes

import com.squareup.moshi.JsonClass

/**
 * One queued, not-yet-acknowledged note save (#323) — the durable twin of an auto-save that failed
 * offline. It carries the exact persisted fields so a later flush can re-create the request verbatim
 * (POST for a not-yet-created note, PUT for a known id).
 *
 * @property id         the note id, or null for a brand-new note that has not been created on the
 *                      server yet (then the entry lives under [NEW_KEY] in the queue map)
 * @property title      the persisted title (trimmed on the wire by the ViewModel, like the live save)
 * @property content    the persisted Markdown body
 * @property tags       the persisted tag list
 * @property folder     the persisted folder label ("" clears it; the backend maps blank ⇒ null)
 * @property visibility SHARED | PRIVATE
 * @property at         wall-clock millis of the failed save — the latest-wins tiebreaker and the
 *                      guard against a stale flush dropping a newer queued edit
 */
@JsonClass(generateAdapter = true)
data class PendingNote(
    val id: String?,
    val title: String,
    val content: String,
    val tags: List<String>,
    val folder: String,
    val visibility: String,
    val at: Long,
)

/** Sentinel key for a not-yet-created note (no id). Only one draft is ever open, so a single slot
 *  suffices; a later create migrates the entry to its real id (mirrors the web client's NEW_KEY). */
const val NEW_KEY = "__new__"

/**
 * Durable, key-value backing store for the offline note-save queue (key = note id, or [NEW_KEY] for a
 * not-yet-created draft → the failed save).
 *
 * Mirrors the web's `localStorage['homebase_notes_pending']` blob and the shopping
 * [com.homebase.android.data.shopping.ShoppingPendingStore]: if the last auto-save fails offline the
 * edit must not be silently lost, so it is persisted here and retried on every connectivity signal
 * until it lands (see [com.homebase.android.ui.notes.NotesViewModel]).
 *
 * Implemented over [android.content.SharedPreferences] (a tiny JSON map, no new dependency) like the
 * shopping store. The interface keeps the queue logic unit-testable with an in-memory fake. Both
 * methods are `suspend` so the implementation can do its blocking disk I/O off the main thread.
 */
interface NotesPendingStore {
    suspend fun load(): Map<String, PendingNote>
    suspend fun save(pending: Map<String, PendingNote>)
}
