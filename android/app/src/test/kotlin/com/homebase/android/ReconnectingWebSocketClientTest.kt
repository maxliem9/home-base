package com.homebase.android

import com.homebase.android.data.websocket.OkHttp
import com.homebase.android.data.websocket.ReconnectingWebSocketClient
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Drives the reconnect state machine deterministically: [FakeClient] overrides the socket-opening
 * seam so no real network is involved, captures the [WebSocketListener] so its callbacks can be fired
 * by hand, and counts how many sockets were opened. Time is virtual via the test scheduler, so the
 * exponential backoff is verified without real waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectingWebSocketClientTest {

    private class FakeClient(scope: CoroutineScope) :
        ReconnectingWebSocketClient<String>("http://host/api/v1", OkHttp(mockk(relaxed = true)), scope) {

        override val path = "/ws/test"
        val sockets = mutableListOf<WebSocket>()
        lateinit var listener: WebSocketListener

        override fun parse(text: String): String = text

        override fun openSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return mockk<WebSocket>(relaxed = true).also { sockets += it }
        }

        /** The socket the base currently regards as live — the one whose callbacks are not stale. */
        fun current(): WebSocket = sockets.last()
    }

    private fun response() = mockk<Response>(relaxed = true)

    /** Build a client whose reconnect coroutine runs on the test scheduler, so virtual time drives it. */
    private fun TestScope.newClient() = FakeClient(CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun `connect opens exactly one socket`() = runTest {
        val client = newClient()
        client.connect("tok")
        assertEquals(1, client.sockets.size)
    }

    @Test
    fun `onClosed reconnects after backoff but not before`() = runTest {
        val client = newClient()
        client.connect("tok")
        client.listener.onClosed(client.current(), 1006, "gone")

        advanceTimeBy(999)
        runCurrent()
        assertEquals("must wait out the 1s backoff", 1, client.sockets.size)

        advanceUntilIdle()
        assertEquals("reconnects once backoff elapses", 2, client.sockets.size)
    }

    @Test
    fun `onFailure reconnects`() = runTest {
        val client = newClient()
        client.connect("tok")
        client.listener.onFailure(client.current(), IOException("network down"), null)

        advanceUntilIdle()
        assertEquals(2, client.sockets.size)
    }

    @Test
    fun `ensureConnected reconnects immediately and cancels the pending backoff`() = runTest {
        val client = newClient()
        client.connect("tok")
        client.listener.onClosed(client.current(), 1006, "gone")

        // Resume arrives before the backoff fires: reconnect now, do not wait.
        client.ensureConnected()
        assertEquals(2, client.sockets.size)

        // The superseded backoff job must not fire a second, duplicate socket.
        advanceUntilIdle()
        assertEquals(2, client.sockets.size)
    }

    @Test
    fun `ensureConnected is a no-op while the socket is up`() = runTest {
        val client = newClient()
        client.connect("tok")
        client.ensureConnected()
        assertEquals(1, client.sockets.size)
    }

    @Test
    fun `disconnect stops further reconnects`() = runTest {
        val client = newClient()
        client.connect("tok")
        val live = client.current()
        client.disconnect()

        // A late onClosed for the socket we closed ourselves must not reopen it.
        client.listener.onClosed(live, 1000, "bye")
        advanceUntilIdle()
        assertEquals(1, client.sockets.size)
    }

    @Test
    fun `backoff grows on repeated failures and onOpen resets it`() = runTest {
        val client = newClient()
        client.connect("tok")

        // First drop → 1s backoff.
        client.listener.onClosed(client.current(), 1006, "gone")
        advanceUntilIdle()
        assertEquals(2, client.sockets.size)

        // Second drop without a successful open → 2s backoff.
        client.listener.onClosed(client.current(), 1006, "gone")
        advanceTimeBy(1999)
        runCurrent()
        assertEquals("second backoff is 2s, not 1s", 2, client.sockets.size)
        advanceUntilIdle()
        assertEquals(3, client.sockets.size)

        // A successful open resets the counter, so the next drop waits only 1s again.
        client.listener.onOpen(client.current(), response())
        client.listener.onClosed(client.current(), 1006, "gone")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("backoff reset to 1s after onOpen", 4, client.sockets.size)
    }

    // --- #553: multi-consumer SharedFlow + reference-counted lifecycle ---

    @Test
    fun `events fan out to every collector`() = runTest {
        val client = newClient()
        client.connect("tok")
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        backgroundScope.launch { client.events.collect { a += it } }
        backgroundScope.launch { client.events.collect { b += it } }
        runCurrent() // both must be subscribed before the emit (SharedFlow replay=0)

        client.listener.onMessage(client.current(), "hello")
        runCurrent()

        // Single-consumer receiveAsFlow would have delivered "hello" to only one of them.
        assertEquals(listOf("hello"), a)
        assertEquals(listOf("hello"), b)
    }

    @Test
    fun `a shared client stays open until the last consumer disconnects`() = runTest {
        val client = newClient()
        client.connect("tok") // consumer A
        client.connect("tok") // consumer B — shares the same live socket, no second open
        assertEquals("second consumer reuses the socket", 1, client.sockets.size)

        client.disconnect() // A leaves; B still holds it → socket survives and keeps reconnecting
        client.listener.onClosed(client.current(), 1006, "gone")
        advanceUntilIdle()
        assertEquals("reconnects while a consumer remains", 2, client.sockets.size)

        client.disconnect() // B leaves; last consumer gone → tear down, no more reconnects
        client.listener.onClosed(client.current(), 1000, "bye")
        advanceUntilIdle()
        assertEquals("no reconnect after the last consumer left", 2, client.sockets.size)
    }
}
