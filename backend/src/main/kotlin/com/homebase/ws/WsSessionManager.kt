package com.homebase.ws

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WsSessionManager {
    private val mutex = Mutex()
    private val sessions = mutableSetOf<DefaultWebSocketServerSession>()

    suspend fun add(session: DefaultWebSocketServerSession) = mutex.withLock { sessions.add(session) }
    suspend fun remove(session: DefaultWebSocketServerSession) = mutex.withLock { sessions.remove(session) }

    suspend fun broadcast(text: String) {
        val snapshot = mutex.withLock { sessions.toSet() }
        for (session in snapshot) {
            runCatching { session.send(Frame.Text(text)) }
        }
    }
}
