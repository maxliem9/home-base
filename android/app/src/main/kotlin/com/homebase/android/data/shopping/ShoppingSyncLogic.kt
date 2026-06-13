package com.homebase.android.data.shopping

import com.homebase.android.data.repository.ApiException
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Wall-clock seam so the ViewModel's `at` millis and the optimistic `checkedAt` ISO string can be
 * pinned deterministically in tests.
 */
fun interface ShoppingClock {
    fun nowMillis(): Long

    fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMillis()))

    companion object {
        val System = ShoppingClock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Pure, Android-free decision logic for the offline check-off queue, kept separate from the
 * ViewModel so it can be unit-tested without Robolectric. Two concerns live here:
 *
 *  1. [classifyFlush] — what to do with one failed queued PUT (keep & retry vs. drop).
 *  2. [PendingQueue] — the latest-wins map of not-yet-acknowledged check intents.
 */

/** What a queued PUT result means for the queue entry. */
enum class FlushDecision {
    /** Landed — remove the entry (unless re-toggled meanwhile). */
    DROP_DONE,

    /** Transient failure (offline / 5xx) — keep the entry and retry on the next signal. */
    KEEP_RETRY,

    /**
     * Terminal client error (e.g. 404 — item already deleted server-side) — retrying can never
     * succeed, so drop the entry instead of looping forever.
     */
    DROP_TERMINAL,
}

/**
 * Decide what to do with a failed queued check-off, mirroring the web `flushPending` branches:
 * transport rejects (offline) and 5xx are the "silently lost check-off" this feature prevents, so
 * they are kept and retried; a terminal 4xx (except auth, handled by the caller) is dropped.
 *
 * The repository surfaces a raw [HttpException] for non-2xx responses (no `mapHttpError` on the
 * shopping path) and wraps transport/parse failures in [ApiException]; we unwrap to find the real
 * cause. A 401 is treated as retryable here — the ViewModel re-checks auth separately and a token
 * refresh/re-login can make the retry land, so we must not silently drop the user's check.
 */
fun classifyFlush(error: Throwable): FlushDecision {
    val http = error as? HttpException ?: (error as? ApiException)?.cause as? HttpException
    if (http != null) {
        val code = http.code()
        return when {
            code == 401 || code == 408 || code == 429 -> FlushDecision.KEEP_RETRY
            code in 500..599 -> FlushDecision.KEEP_RETRY
            code in 400..499 -> FlushDecision.DROP_TERMINAL
            else -> FlushDecision.KEEP_RETRY
        }
    }
    // Transport/timeout (IOException, possibly wrapped) → offline → keep & retry.
    val io = error as? IOException ?: (error as? ApiException)?.cause as? IOException
    if (io != null) return FlushDecision.KEEP_RETRY
    // Unknown (e.g. a parse error). Keep & retry rather than dropping the user's intent;
    // a genuinely poisoned entry is rare and the periodic backstop will keep trying — acceptable
    // versus silently losing a check-off.
    return FlushDecision.KEEP_RETRY
}

/**
 * Latest-wins map of pending check intents (item id → [PendingCheck]). Immutable snapshots; every
 * mutation returns a new [PendingQueue] so it composes with `MutableStateFlow.update`. Backed by a
 * plain map — persistence is the caller's job via [ShoppingPendingStore].
 */
data class PendingQueue(val entries: Map<String, PendingCheck> = emptyMap()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    operator fun get(id: String): PendingCheck? = entries[id]

    operator fun contains(id: String): Boolean = id in entries

    /** Record (or overwrite) the intent for [id] — last desired state wins. */
    fun enqueue(id: String, check: PendingCheck): PendingQueue =
        PendingQueue(entries + (id to check))

    /** Drop the entry for [id] (idempotent). */
    fun dequeue(id: String): PendingQueue =
        if (id in entries) PendingQueue(entries - id) else this

    /** Drop every entry whose id is in [ids] — used when items/lists are deleted. */
    fun dequeueAll(ids: Collection<String>): PendingQueue {
        if (ids.isEmpty() || entries.isEmpty()) return this
        val next = entries - ids.toSet()
        return if (next.size == entries.size) this else PendingQueue(next)
    }

    /**
     * Drop [id] only if its intent is still [expected] — i.e. the user did not re-toggle while a
     * flush for the old intent was in flight (a newer `at` must survive). Idempotent.
     */
    fun dequeueIfUnchanged(id: String, expected: PendingCheck): PendingQueue =
        if (entries[id] == expected) dequeue(id) else this
}
