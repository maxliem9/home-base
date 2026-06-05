package com.homebase.android.data.websocket

import com.homebase.android.data.model.NoteDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class NotesWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<NotesWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        data class NoteCreated(val note: NoteDto) : WsEvent()
        data class NoteUpdated(val note: NoteDto) : WsEvent()
        data class NoteDeleted(val note: NoteDto) : WsEvent()
    }

    override val path = "/ws/notes"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val msg = moshi.adapter(WsPayload::class.java).fromJson(text) ?: return null
        return when (msg.type) {
            "NOTE_CREATED" -> msg.payload?.let { WsEvent.NoteCreated(it) }
            "NOTE_UPDATED" -> msg.payload?.let { WsEvent.NoteUpdated(it) }
            "NOTE_DELETED" -> msg.payload?.let { WsEvent.NoteDeleted(it) }
            else -> null
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(val type: String, val payload: NoteDto? = null)
}
