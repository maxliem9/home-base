package com.homebase

import com.homebase.ws.WsSessionManager
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the #557 broadcast hardening: the fan-out sends per-session concurrently with a timeout, and a
 * session that can't accept a frame is dropped + closed on the spot instead of stalling the others
 * until the ping timeout. Uses `runTest` virtual time so the 10s send timeout fires instantly.
 */
class WsSessionManagerTest {

    private fun session(): DefaultWebSocketServerSession = mockk(relaxed = true)

    @Test
    fun `broadcast delivers the frame to every live session`() = runTest {
        val a = session()
        val b = session()
        val capA = slot<Frame>()
        val capB = slot<Frame>()
        coEvery { a.send(capture(capA)) } returns Unit
        coEvery { b.send(capture(capB)) } returns Unit
        WsSessionManager.add("t-deliver", a)
        WsSessionManager.add("t-deliver", b)

        WsSessionManager.broadcast("t-deliver", "hello")

        assertEquals("hello", (capA.captured as Frame.Text).readText())
        assertEquals("hello", (capB.captured as Frame.Text).readText())

        WsSessionManager.remove("t-deliver", a)
        WsSessionManager.remove("t-deliver", b)
    }

    @Test
    fun `a session that never accepts the frame is dropped and closed without blocking others`() = runTest {
        val good = session()
        val dead = session()
        // `dead` hangs well past the 10s send timeout; `good` accepts immediately.
        coEvery { dead.send(any()) } coAnswers { delay(30_000) }
        coEvery { good.send(any()) } returns Unit
        WsSessionManager.add("t-dead", good)
        WsSessionManager.add("t-dead", dead)

        // Concurrent + bounded: `good` is delivered even though `dead` is stuck; `dead` times out,
        // gets removed and closed. (Virtual time — the 10s timeout elapses instantly.)
        WsSessionManager.broadcast("t-dead", "x")
        // `dead` was removed, so a second broadcast never touches it again.
        WsSessionManager.broadcast("t-dead", "y")

        coVerify(atLeast = 2) { good.send(match { it is Frame.Text }) }   // both broadcasts reached the healthy peer
        coVerify(exactly = 1) { dead.send(match { it is Frame.Text }) }   // only the first; then it was dropped (removed)
        coVerify(exactly = 1) { dead.send(match { it is Frame.Close }) }  // …and closed (close() sends a Close frame)

        WsSessionManager.remove("t-dead", good)
    }
}
