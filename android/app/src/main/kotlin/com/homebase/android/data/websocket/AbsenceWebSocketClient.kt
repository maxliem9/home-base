package com.homebase.android.data.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Absence planner real-time channel. The backend sends a single message shape
 * `{ "type": "ABSENCE_CHANGED" }` on every mutation; clients react by refetching
 * the full snapshot (the planner state is small and always loaded whole). The
 * payload is trivial, so it is parsed with org.json rather than Moshi.
 */
class AbsenceWebSocketClient(
    private val baseUrl: String,
    private val okHttp: OkHttp,
) {
    sealed class WsEvent {
        data object Changed : WsEvent()
    }

    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/absence"

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    if (JSONObject(text).optString("type") == "ABSENCE_CHANGED") {
                        eventChannel.trySend(WsEvent.Changed)
                    }
                }
            }
        }
        webSocket = okHttp.client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(1000, null)
        webSocket = null
    }
}
