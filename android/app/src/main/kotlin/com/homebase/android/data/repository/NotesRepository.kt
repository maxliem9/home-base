package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.websocket.NotesWebSocketClient
import kotlinx.coroutines.flow.Flow

class NotesRepository(
    private val api: HomeBaseApi,
    private val wsClient: NotesWebSocketClient,
) {
    val incomingEvents: Flow<NotesWebSocketClient.WsEvent> = wsClient.events

    suspend fun getNotes(query: String? = null): Result<List<NoteDto>> =
        runCatching { api.getNotes(query?.takeIf { it.isNotBlank() }) }

    suspend fun createNote(request: CreateNoteRequest): Result<NoteDto> =
        runCatching { api.createNote(request) }

    suspend fun updateNote(id: String, request: UpdateNoteRequest): Result<NoteDto> =
        runCatching { api.updateNote(id, request) }

    suspend fun deleteNote(id: String): Result<Unit> =
        runCatching { api.deleteNote(id) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun disconnectWebSocket() = wsClient.disconnect()
}
