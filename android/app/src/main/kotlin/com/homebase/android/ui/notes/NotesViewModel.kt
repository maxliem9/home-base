package com.homebase.android.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.BuildConfig
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.NoteImageDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.websocket.NotesWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotesUiState(
    val notes: List<NoteDto> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class NotesViewModel(
    private val repository: NotesRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState(isLoading = true))
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getNotes(_uiState.value.query)
                .onSuccess { notes -> _uiState.update { it.copy(notes = notes, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        load()
    }

    fun saveNote(
        id: String?,
        title: String,
        content: String,
        tags: List<String>,
        folder: String,
        visibility: String,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            // Always send a (possibly empty) string for folder: the backend trims it and maps
            // blank ⇒ null, so this both sets a folder and clears one when the field is emptied
            // (mirrors the web client).
            val folderValue = folder.trim()
            val result = if (id == null) {
                repository.createNote(
                    CreateNoteRequest(
                        title = title.trim(),
                        content = content,
                        tags = tags,
                        folder = folderValue,
                        visibility = visibility,
                    )
                )
            } else {
                repository.updateNote(
                    id,
                    UpdateNoteRequest(
                        title = title.trim(),
                        content = content,
                        tags = tags,
                        folder = folderValue,
                        visibility = visibility,
                    )
                )
            }
            result
                .onSuccess { note -> upsert(note) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            repository.deleteNote(id)
                .onSuccess {
                    _uiState.update { state -> state.copy(notes = state.notes.filter { it.id != id }) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun uploadImage(noteId: String, bytes: ByteArray, filename: String, contentType: String) {
        viewModelScope.launch {
            repository.uploadImage(noteId, bytes, filename, contentType)
                .onSuccess { note -> upsert(note) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun removeImage(noteId: String, imageId: String) {
        viewModelScope.launch {
            repository.deleteImage(noteId, imageId)
                .onSuccess { note -> upsert(note) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Authenticated URL for an image. Coil/<img> can set neither an Authorization header nor a
     * WebSocket subprotocol, so the backend accepts the JWT via the `?token=` query param for these
     * image loads only. (The web client moved its WebSocket auth to the Sec-WebSocket-Protocol
     * header; `?token=` is now the image-only fallback.)
     */
    fun imageUrl(image: NoteImageDto): String =
        BuildConfig.BASE_URL.trimEnd('/') + "/notes/${image.noteId}/images/${image.id}?token=$token"

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun upsert(note: NoteDto) {
        _uiState.update { state ->
            val notes = if (state.notes.any { it.id == note.id }) {
                state.notes.map { if (it.id == note.id) note else it }
            } else {
                listOf(note) + state.notes
            }
            state.copy(notes = notes)
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is NotesWebSocketClient.WsEvent.NoteCreated -> upsert(event.note)
                    is NotesWebSocketClient.WsEvent.NoteUpdated -> upsert(event.note)
                    is NotesWebSocketClient.WsEvent.NoteDeleted ->
                        _uiState.update { state ->
                            state.copy(notes = state.notes.filter { it.id != event.note.id })
                        }
                }
            }
        }
    }

    /** Reconnect the channel if it dropped — called from the UI when the app returns to the foreground. */
    fun ensureConnected() = repository.ensureWebSocketConnected()

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
