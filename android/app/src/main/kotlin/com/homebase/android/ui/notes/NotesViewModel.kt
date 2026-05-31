package com.homebase.android.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
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
        visibility: String,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val result = if (id == null) {
                repository.createNote(
                    CreateNoteRequest(
                        title = title.trim(),
                        content = content,
                        tags = tags,
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

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
