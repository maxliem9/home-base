package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.websocket.NotesWebSocketClient
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

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

    suspend fun uploadImage(
        noteId: String,
        bytes: ByteArray,
        filename: String,
        contentType: String,
    ): Result<NoteDto> = runCatching {
        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = filename,
            body = bytes.toRequestBody(contentType.toMediaTypeOrNull()),
        )
        api.uploadNoteImage(noteId, part)
    }

    suspend fun deleteImage(noteId: String, imageId: String): Result<NoteDto> =
        runCatching { api.deleteNoteImage(noteId, imageId) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()
}
