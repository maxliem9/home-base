package com.homebase.android.data.notes

import com.homebase.android.data.repository.ApiException
import retrofit2.HttpException
import java.io.IOException

/**
 * Wall-clock seam so the ViewModel's `at` millis can be pinned deterministically in tests. Mirrors
 * [com.homebase.android.data.shopping.ShoppingClock].
 */
fun interface NotesClock {
    fun nowMillis(): Long

    companion object {
        val System = NotesClock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Pure, Android-free decision logic for the offline note-save queue (#323), kept separate from the
 * ViewModel so it can be unit-tested without Robolectric. Two concerns live here:
 *
 *  1. [classifyNoteFlush] — what to do with one failed queued save (keep & retry vs. drop).
 *  2. [PendingNoteQueue] — the latest-wins map of not-yet-acknowledged saves.
 */

/**
 * What a *failed* queued save means for the queue entry. ([classifyNoteFlush] is only consulted on
 * failure; a success drops the entry directly in the ViewModel's flush loop.)
 */
enum class NoteFlushDecision {
    /** Transient failure (offline / 5xx) — keep the entry and retry on the next signal. */
    KEEP_RETRY,

    /**
     * Terminal client error (e.g. a 400 validation reject) — retrying can never succeed, so drop the
     * entry instead of looping forever. (A 404 PUT for a not-yet-existing id can't happen here: an
     * unsaved draft is queued as a create under [NEW_KEY], never as a PUT.)
     */
    DROP_TERMINAL,
}

/**
 * Decide what to do with a failed queued note save, mirroring the web `flushPendingNotes` branches
 * and the shopping `classifyFlush`: transport rejects (offline) and 5xx are the "silently lost edit"
 * this feature prevents, so they are kept and retried; a terminal 4xx (except auth) is dropped.
 *
 * The repository surfaces a raw [HttpException] for non-2xx responses and wraps transport/parse
 * failures in [ApiException]; we unwrap to find the real cause. A 401 is treated as retryable — the
 * ViewModel re-checks auth separately and a token refresh/re-login can make the retry land, so we
 * must not silently drop the user's edit.
 */
fun classifyNoteFlush(error: Throwable): NoteFlushDecision {
    val http = error as? HttpException ?: (error as? ApiException)?.cause as? HttpException
    if (http != null) {
        val code = http.code()
        return when {
            code == 401 || code == 408 || code == 429 -> NoteFlushDecision.KEEP_RETRY
            code in 500..599 -> NoteFlushDecision.KEEP_RETRY
            code in 400..499 -> NoteFlushDecision.DROP_TERMINAL
            else -> NoteFlushDecision.KEEP_RETRY
        }
    }
    // Transport/timeout (IOException, possibly wrapped) → offline → keep & retry.
    val io = error as? IOException ?: (error as? ApiException)?.cause as? IOException
    if (io != null) return NoteFlushDecision.KEEP_RETRY
    // Unknown (e.g. a parse error). Keep & retry rather than dropping the user's intent; the periodic
    // backstop will keep trying — acceptable versus silently losing an edit.
    return NoteFlushDecision.KEEP_RETRY
}

/**
 * Latest-wins map of pending note saves (note id / [NEW_KEY] → [PendingNote]). Immutable snapshots;
 * every mutation returns a new [PendingNoteQueue] so it composes with `MutableStateFlow.update`.
 * Persistence is the caller's job via [NotesPendingStore]. Mirrors the shopping `PendingQueue`.
 */
data class PendingNoteQueue(val entries: Map<String, PendingNote> = emptyMap()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    operator fun get(key: String): PendingNote? = entries[key]

    operator fun contains(key: String): Boolean = key in entries

    /** The key an entry for [id] lives under: its id, or [NEW_KEY] for a not-yet-created note. */
    fun keyFor(id: String?): String = id ?: NEW_KEY

    /** Record (or overwrite) the save for [pending] — last edit wins. Keyed by id / [NEW_KEY]. */
    fun enqueue(pending: PendingNote): PendingNoteQueue =
        PendingNoteQueue(entries + (keyFor(pending.id) to pending))

    /** Drop the entry under [key] (idempotent). */
    fun dequeue(key: String): PendingNoteQueue =
        if (key in entries) PendingNoteQueue(entries - key) else this

    /**
     * Drop [key] only if its entry is still [expected] — i.e. the user did not type a newer edit
     * (queued with a fresh `at`) while a flush for the old body was in flight. Idempotent.
     */
    fun dequeueIfUnchanged(key: String, expected: PendingNote): PendingNoteQueue =
        if (entries[key] == expected) dequeue(key) else this
}
