package com.homebase.android.data.websocket

import com.homebase.android.data.model.TodoDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*

class TodoWebSocketClient(
    private val baseUrl: String,
    private val okHttp: OkHttp,
) {
    sealed class WsEvent {
        data class TodoCreated(val todo: TodoDto) : WsEvent()
        data class TodoUpdated(val todo: TodoDto) : WsEvent()
        data class TodoDeleted(val todo: TodoDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().build()
    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/todos"

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val adapter = moshi.adapter(WsPayload::class.java)
                    val msg = adapter.fromJson(text) ?: return
                    val event = when (msg.type) {
                        "TODO_CREATED" -> msg.payload?.let { WsEvent.TodoCreated(it) }
                        "TODO_UPDATED" -> msg.payload?.let { WsEvent.TodoUpdated(it) }
                        "TODO_DELETED" -> msg.payload?.let { WsEvent.TodoDeleted(it) }
                        else -> null
                    }
                    event?.let { eventChannel.trySend(it) }
                }
            }
        }
        webSocket = okHttp.client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(1000, null)
        webSocket = null
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(val type: String, val payload: TodoDto? = null)
}

class OkHttp(val client: OkHttpClient)
