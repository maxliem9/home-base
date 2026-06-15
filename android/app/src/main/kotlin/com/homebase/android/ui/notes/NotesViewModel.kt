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

    /**
     * Pull-to-refresh entry point (#269). Suspends until the refetch completes so the UI's refresh
     * indicator can spin for the duration; no full-screen spinner (the list stays visible) but it
     * does surface a fetch error like load(), since it's user-triggered. Respects the active query.
     */
    suspend fun refresh() {
        repository.getNotes(_uiState.value.query)
            .onSuccess { notes -> _uiState.update { it.copy(notes = notes, error = null) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
    }

    /**
     * Silent background re-sync of the notes list (#269). Fires on every WS (re)connect
     * (`onConnected`) and on app/screen resume ([ensureConnected]). A note created/edited/deleted on
     * the web or another device while our socket was dead (Doze / mobile-network change / backend
     * restart) sends a NOTE_* frame we never receive — without this refetch the list would stay stale
     * until logout/login. Unlike [load] this never flips `isLoading` and leaves existing notes +
     * `error` untouched on a transient failure (the next trigger retries). Respects the active query.
     */
    private fun syncFromServer() {
        viewModelScope.launch {
            repository.getNotes(_uiState.value.query)
                .onSuccess { notes -> _uiState.update { it.copy(notes = notes) } }
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
    fun imageUrl(noteId: String, imageId: String): String =
        BuildConfig.BASE_URL.trimEnd('/') + "/notes/$noteId/images/$imageId?token=$token"

    fun imageUrl(image: NoteImageDto): String = imageUrl(image.noteId, image.id)

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
        // Re-sync on every (re)connect — the "server reachable again" signal (#269, mirrors the time
        // channel + shopping queue flush). The first connect also fires this; that one re-sync
        // overlaps load()'s fetch (harmless — a cheap GET at cold start), and every later reconnect
        // then reliably re-syncs without bespoke state.
        repository.setWebSocketOnConnected { syncFromServer() }
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

    /**
     * Called from the UI when the app returns to the foreground (#269). Reconnects the channel if it
     * dropped **and** re-syncs from the server: a reconnect fires `onConnected` → [syncFromServer],
     * but if the socket survived the background no callback fires, so we also refetch here. Either way
     * the list matches the server after a backgrounded change elsewhere.
     */
    fun ensureConnected() {
        repository.ensureWebSocketConnected()
        syncFromServer()
    }

    override fun onCleared() {
        super.onCleared()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }
}
