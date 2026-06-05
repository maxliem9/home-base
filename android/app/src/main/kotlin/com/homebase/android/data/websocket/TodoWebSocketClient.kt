package com.homebase.android.data.websocket

import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
        data class ListCreated(val list: TodoListDto) : WsEvent()
        data class ListUpdated(val list: TodoListDto) : WsEvent()
        data class ListDeleted(val list: TodoListDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
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
                    val type = moshi.adapter(TypeEnvelope::class.java).fromJson(text)?.type ?: return
                    val event = when (type) {
                        "TODO_CREATED" -> todo(text)?.let { WsEvent.TodoCreated(it) }
                        "TODO_UPDATED" -> todo(text)?.let { WsEvent.TodoUpdated(it) }
                        "TODO_DELETED" -> todo(text)?.let { WsEvent.TodoDeleted(it) }
                        "TODO_LIST_CREATED" -> list(text)?.let { WsEvent.ListCreated(it) }
                        "TODO_LIST_UPDATED" -> list(text)?.let { WsEvent.ListUpdated(it) }
                        "TODO_LIST_DELETED" -> list(text)?.let { WsEvent.ListDeleted(it) }
                        else -> null
                    }
                    event?.let { eventChannel.trySend(it) }
                }
            }
        }
        webSocket = okHttp.client.newWebSocket(request, listener)
    }

    private fun todo(text: String): TodoDto? =
        moshi.adapter(TodoEnvelope::class.java).fromJson(text)?.payload

    private fun list(text: String): TodoListDto? =
        moshi.adapter(ListEnvelope::class.java).fromJson(text)?.payload

    fun disconnect() {
        webSocket?.close(1000, null)
        webSocket = null
    }

    @JsonClass(generateAdapter = true)
    internal data class TypeEnvelope(val type: String)

    @JsonClass(generateAdapter = true)
    internal data class TodoEnvelope(val type: String, val payload: TodoDto? = null)

    @JsonClass(generateAdapter = true)
    internal data class ListEnvelope(val type: String, val payload: TodoListDto? = null)
}

class OkHttp(val client: OkHttpClient)
