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
import retrofit2.HttpException

class NotesRepository(
    private val api: HomeBaseApi,
    private val wsClient: NotesWebSocketClient,
) {
    val incomingEvents: Flow<NotesWebSocketClient.WsEvent> = wsClient.events

    suspend fun getNotes(query: String? = null): Result<List<NoteDto>> =
        apiCatching { api.getNotes(query?.takeIf { it.isNotBlank() }) }

    suspend fun createNote(request: CreateNoteRequest): Result<NoteDto> =
        apiCatching { api.createNote(request) }

    suspend fun updateNote(id: String, request: UpdateNoteRequest): Result<NoteDto> =
        apiCatching { api.updateNote(id, request) }

    suspend fun deleteNote(id: String): Result<Unit> =
        apiCatching { api.deleteNote(id) }

    suspend fun uploadImage(
        noteId: String,
        bytes: ByteArray,
        filename: String,
        contentType: String,
    ): Result<NoteDto> = apiCatching {
        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = filename,
            body = bytes.toRequestBody(contentType.toMediaTypeOrNull()),
        )
        api.uploadNoteImage(noteId, part)
    }

    suspend fun deleteImage(noteId: String, imageId: String): Result<NoteDto> =
        apiCatching { api.deleteNoteImage(noteId, imageId) }

    // --- File attachments (#437) ---

    suspend fun uploadAttachment(
        noteId: String,
        bytes: ByteArray,
        filename: String,
        contentType: String,
    ): Result<NoteDto> = apiCatching(mapHttpError = ::germanAttachmentUploadError) {
        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = filename,
            body = bytes.toRequestBody(contentType.toMediaTypeOrNull()),
        )
        api.uploadNoteAttachment(noteId, part)
    }

    suspend fun deleteAttachment(noteId: String, attachmentId: String): Result<NoteDto> =
        apiCatching { api.deleteNoteAttachment(noteId, attachmentId) }

    /** Download an attachment's raw bytes (the caller opens them with the system viewer). */
    suspend fun downloadAttachment(noteId: String, attachmentId: String): Result<ByteArray> =
        apiCatching { api.downloadNoteAttachment(noteId, attachmentId).use { it.bytes() } }

    /**
     * Map a failed attachment upload to German text. The backend rejects oversize files with
     * 413/`ATTACHMENT_TOO_LARGE` and disallowed types with 415/`UNSUPPORTED_TYPE` (#431); branch on
     * the stable HTTP status (more robust than the body code). Wording mirrors web `notes.attachment*`.
     */
    private fun germanAttachmentUploadError(e: HttpException): String = when (e.code()) {
        413 -> "Datei ist zu groß (max. 10 MB)."
        415 -> "Dateityp nicht erlaubt (PDF, Text, Office …)."
        else -> "Upload fehlgeschlagen."
    }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /**
     * Register a "socket (re)connected, server reachable again" callback (#269). The ViewModel uses
     * it to silently refetch the notes list after a drop, so a note created/edited on another device
     * while our socket was dead (Doze / mobile-network change / backend restart) shows up instead of
     * leaving stale data on screen. Mirrors the time + shopping channels.
     */
    fun setWebSocketOnConnected(onConnected: (() -> Unit)?) {
        wsClient.onConnected = onConnected
    }
}
