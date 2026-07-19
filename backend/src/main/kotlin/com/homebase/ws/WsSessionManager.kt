package com.homebase.ws

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory registry of the live WebSocket sessions per sync channel, plus the fan-out that pushes a
 * broadcast to all of them.
 *
 * **Single-instance invariant (#557):** this is a process-local singleton — sessions live only in the
 * heap of the one backend process. A second replica would each hold half the sessions and neither
 * could reach the other's clients, silently breaking sync. HomeBase runs deliberately single-instance
 * (see docs/DEPLOYMENT.md); a future scale-out would replace this fan-out with a shared bus (Redis
 * pub/sub or Postgres LISTEN/NOTIFY).
 */
object WsSessionManager {
    private val mutex = Mutex()
    private val sessionsByChannel = mutableMapOf<String, MutableSet<DefaultWebSocketServerSession>>()

    /**
     * Per-session send budget for a broadcast. A client that can't accept a small text frame within
     * this window is treated as dead and dropped, rather than stalling the fan-out until the ~15s ping
     * timeout. Comfortably above any real LAN round-trip; well under the ping timeout.
     */
    private val sendTimeout = 10.seconds

    suspend fun add(channel: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessionsByChannel.getOrPut(channel) { mutableSetOf() }.add(session)
    }

    suspend fun remove(channel: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessionsByChannel[channel]?.remove(session)
        if (sessionsByChannel[channel]?.isEmpty() == true) {
            sessionsByChannel.remove(channel)
        }
    }

    /**
     * Push [text] to every session on [channel]. Sends run **concurrently** (#557): a single slow or
     * dead client used to delay every subsequent recipient because the loop sent serially. Each send is
     * bounded by [sendTimeout]; on failure or timeout the session is presumed dead and removed +
     * closed immediately (the socket's own `finally` also removes it — [remove] is idempotent), so a
     * broken peer is cleaned up now instead of lingering until the ping timeout.
     */
    suspend fun broadcast(channel: String, text: String): Unit = coroutineScope {
        val snapshot = mutex.withLock { sessionsByChannel[channel].orEmpty().toSet() }
        snapshot.map { session ->
            async {
                val ok = runCatching { withTimeout(sendTimeout) { session.send(Frame.Text(text)) } }.isSuccess
                if (!ok) {
                    remove(channel, session)
                    runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "send failed")) }
                }
            }
        }.awaitAll()
    }
}
