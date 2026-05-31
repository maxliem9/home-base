package com.homebase.ws

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WsSessionManager {
    private val mutex = Mutex()
    private val sessionsByChannel = mutableMapOf<String, MutableSet<DefaultWebSocketServerSession>>()

    suspend fun add(channel: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessionsByChannel.getOrPut(channel) { mutableSetOf() }.add(session)
    }

    suspend fun remove(channel: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessionsByChannel[channel]?.remove(session)
        if (sessionsByChannel[channel]?.isEmpty() == true) {
            sessionsByChannel.remove(channel)
        }
    }

    suspend fun broadcast(channel: String, text: String) {
        val snapshot = mutex.withLock { sessionsByChannel[channel].orEmpty().toSet() }
        for (session in snapshot) {
            runCatching { session.send(Frame.Text(text)) }
        }
    }
}
