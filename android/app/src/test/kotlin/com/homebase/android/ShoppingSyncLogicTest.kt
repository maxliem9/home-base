package com.homebase.android

import com.homebase.android.data.repository.AppError
import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.shopping.FlushDecision
import com.homebase.android.data.shopping.PendingCheck
import com.homebase.android.data.shopping.PendingQueue
import com.homebase.android.data.shopping.classifyFlush
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

/** Pure, Android-free tests for the offline check-off queue logic (issue #170). */
class ShoppingSyncLogicTest {

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())))

    // --- classifyFlush ---------------------------------------------------------------------

    @Test
    fun `offline IOException is kept and retried`() {
        assertEquals(FlushDecision.KEEP_RETRY, classifyFlush(IOException("offline")))
    }

    @Test
    fun `network error wrapped in ApiException is kept and retried`() {
        val wrapped = ApiException(AppError.NETWORK, IOException("dns"))
        assertEquals(FlushDecision.KEEP_RETRY, classifyFlush(wrapped))
    }

    @Test
    fun `5xx is kept and retried`() {
        assertEquals(FlushDecision.KEEP_RETRY, classifyFlush(http(503)))
    }

    @Test
    fun `401 is kept and retried (a re-login can make it land)`() {
        assertEquals(FlushDecision.KEEP_RETRY, classifyFlush(http(401)))
    }

    @Test
    fun `429 and 408 are kept and retried`() {
        assertEquals(FlushDecision.KEEP_RETRY, classifyFlush(http(429)))
        assertEquals(FlushDecision.KEEP_RETRY, classifyFlush(http(408)))
    }

    @Test
    fun `404 is terminal and dropped`() {
        assertEquals(FlushDecision.DROP_TERMINAL, classifyFlush(http(404)))
    }

    @Test
    fun `400 is terminal and dropped`() {
        assertEquals(FlushDecision.DROP_TERMINAL, classifyFlush(http(400)))
    }

    @Test
    fun `HttpException wrapped in ApiException is unwrapped and classified by code`() {
        val wrapped = ApiException(AppError.GENERIC, http(404))
        assertEquals(FlushDecision.DROP_TERMINAL, classifyFlush(wrapped))
    }

    // --- PendingQueue ----------------------------------------------------------------------

    @Test
    fun `enqueue records latest-wins intent`() {
        val q = PendingQueue()
            .enqueue("a", PendingCheck(true, 1))
            .enqueue("a", PendingCheck(false, 2))
        assertEquals(PendingCheck(false, 2), q["a"])
        assertEquals(1, q.entries.size)
    }

    @Test
    fun `dequeue removes the entry and is idempotent`() {
        val q = PendingQueue().enqueue("a", PendingCheck(true, 1))
        val after = q.dequeue("a")
        assertFalse("a" in after)
        assertSame("dequeue of a missing key returns the same instance", after, after.dequeue("a"))
    }

    @Test
    fun `dequeueAll drops only the listed ids`() {
        val q = PendingQueue()
            .enqueue("a", PendingCheck(true, 1))
            .enqueue("b", PendingCheck(true, 2))
            .enqueue("c", PendingCheck(true, 3))
        val after = q.dequeueAll(listOf("a", "c"))
        assertTrue("b" in after)
        assertFalse("a" in after)
        assertFalse("c" in after)
    }

    @Test
    fun `dequeueIfUnchanged keeps a re-toggled (newer) intent`() {
        val original = PendingCheck(true, 1)
        val q = PendingQueue().enqueue("a", original)
        // user re-toggled while the original PUT was in flight → newer `at`
        val reToggled = q.enqueue("a", PendingCheck(false, 2))
        val after = reToggled.dequeueIfUnchanged("a", original)
        assertTrue("newer intent must survive a stale dequeue", "a" in after)
        assertEquals(PendingCheck(false, 2), after["a"])
    }

    @Test
    fun `dequeueIfUnchanged drops when intent still matches`() {
        val intent = PendingCheck(true, 1)
        val q = PendingQueue().enqueue("a", intent)
        assertFalse("a" in q.dequeueIfUnchanged("a", intent))
    }
}
