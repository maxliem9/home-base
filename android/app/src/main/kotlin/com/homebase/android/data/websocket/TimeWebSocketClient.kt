package com.homebase.android.data.websocket

import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*

class TimeWebSocketClient(
    private val baseUrl: String,
    private val okHttp: OkHttp,
) {
    sealed class WsEvent {
        data class ProjectCreated(val project: ProjectDto) : WsEvent()
        data class ProjectUpdated(val project: ProjectDto) : WsEvent()
        data class EntryCreated(val entry: TimeEntryDto) : WsEvent()
        data class EntryUpdated(val entry: TimeEntryDto) : WsEvent()
        data class EntryDeleted(val entry: TimeEntryDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().build()
    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/time"

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
                        "PROJECT_CREATED" -> msg.project?.let { WsEvent.ProjectCreated(it) }
                        "PROJECT_UPDATED" -> msg.project?.let { WsEvent.ProjectUpdated(it) }
                        "ENTRY_CREATED" -> msg.entry?.let { WsEvent.EntryCreated(it) }
                        "ENTRY_UPDATED" -> msg.entry?.let { WsEvent.EntryUpdated(it) }
                        "ENTRY_DELETED" -> msg.entry?.let { WsEvent.EntryDeleted(it) }
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
    internal data class WsPayload(
        val type: String,
        val entry: TimeEntryDto? = null,
        val project: ProjectDto? = null,
    )
}
