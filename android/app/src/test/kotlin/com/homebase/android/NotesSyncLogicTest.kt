package com.homebase.android

import com.homebase.android.data.notes.NEW_KEY
import com.homebase.android.data.notes.NoteFlushDecision
import com.homebase.android.data.notes.PendingNote
import com.homebase.android.data.notes.PendingNoteQueue
import com.homebase.android.data.notes.classifyNoteFlush
import com.homebase.android.data.repository.AppError
import com.homebase.android.data.repository.ApiException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Pure, Android-free tests for the offline note auto-save queue logic (#323), mirroring
 *  [ShoppingSyncLogicTest]. */
class NotesSyncLogicTest {

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())))

    private fun pending(id: String?, title: String = "T", at: Long = 1L) = PendingNote(
        id = id, title = title, content = "", tags = emptyList(), folder = "", visibility = "SHARED", at = at,
    )

    // --- classifyNoteFlush -----------------------------------------------------------------

    @Test
    fun `offline IOException is kept and retried`() {
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(IOException("offline")))
    }

    @Test
    fun `network error wrapped in ApiException is kept and retried`() {
        val wrapped = ApiException(AppError.NETWORK, IOException("dns"))
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(wrapped))
    }

    @Test
    fun `5xx is kept and retried`() {
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(http(503)))
    }

    @Test
    fun `401 408 and 429 are kept and retried`() {
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(http(401)))
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(http(408)))
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(http(429)))
    }

    @Test
    fun `400 and 404 are terminal and dropped`() {
        assertEquals(NoteFlushDecision.DROP_TERMINAL, classifyNoteFlush(http(400)))
        assertEquals(NoteFlushDecision.DROP_TERMINAL, classifyNoteFlush(http(404)))
    }

    @Test
    fun `HttpException wrapped in ApiException is unwrapped and classified by code`() {
        assertEquals(NoteFlushDecision.DROP_TERMINAL, classifyNoteFlush(ApiException(AppError.GENERIC, http(400))))
    }

    @Test
    fun `an unknown error is kept and retried rather than silently dropping the edit`() {
        // A parse/unknown error must not lose the user's edit — keep & retry (the backstop keeps trying).
        assertEquals(NoteFlushDecision.KEEP_RETRY, classifyNoteFlush(RuntimeException("???")))
    }

    // --- PendingNoteQueue ------------------------------------------------------------------

    @Test
    fun `enqueue records latest-wins intent under the id`() {
        val q = PendingNoteQueue()
            .enqueue(pending(id = "a", title = "erste", at = 1))
            .enqueue(pending(id = "a", title = "zweite", at = 2))
        assertEquals("zweite", q["a"]?.title)
        assertEquals(1, q.entries.size)
    }

    @Test
    fun `a not-yet-created note is keyed under NEW_KEY`() {
        val q = PendingNoteQueue().enqueue(pending(id = null, title = "Neu"))
        assertTrue(NEW_KEY in q)
        assertEquals("Neu", q[NEW_KEY]?.title)
        assertEquals(NEW_KEY, q.keyFor(null))
        assertEquals("x", q.keyFor("x"))
    }

    @Test
    fun `dequeue removes the entry and is idempotent`() {
        val q = PendingNoteQueue().enqueue(pending(id = "a"))
        val after = q.dequeue("a")
        assertFalse("a" in after)
        assertSame("dequeue of a missing key returns the same instance", after, after.dequeue("a"))
    }

    @Test
    fun `dequeueIfUnchanged keeps a re-edited (newer) intent`() {
        val original = pending(id = "a", title = "alt", at = 1)
        val reEdited = PendingNoteQueue().enqueue(original).enqueue(pending(id = "a", title = "neu", at = 2))
        val after = reEdited.dequeueIfUnchanged("a", original)
        assertTrue("newer intent must survive a stale dequeue", "a" in after)
        assertEquals("neu", after["a"]?.title)
    }

    @Test
    fun `dequeueIfUnchanged drops when intent still matches`() {
        val intent = pending(id = "a")
        val q = PendingNoteQueue().enqueue(intent)
        assertFalse("a" in q.dequeueIfUnchanged("a", intent))
    }
}
