package com.homebase.android.data.websocket

import com.homebase.android.data.model.NoteDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*

class NotesWebSocketClient(
    private val baseUrl: String,
    private val okHttp: OkHttp,
) {
    sealed class WsEvent {
        data class NoteCreated(val note: NoteDto) : WsEvent()
        data class NoteUpdated(val note: NoteDto) : WsEvent()
        data class NoteDeleted(val note: NoteDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/notes"

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
                        "NOTE_CREATED" -> msg.payload?.let { WsEvent.NoteCreated(it) }
                        "NOTE_UPDATED" -> msg.payload?.let { WsEvent.NoteUpdated(it) }
                        "NOTE_DELETED" -> msg.payload?.let { WsEvent.NoteDeleted(it) }
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
    internal data class WsPayload(val type: String, val payload: NoteDto? = null)
}
