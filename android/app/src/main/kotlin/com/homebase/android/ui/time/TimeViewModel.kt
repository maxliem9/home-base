package com.homebase.android.ui.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimeUiState(
    val projects: List<ProjectDto> = emptyList(),
    val entries: List<TimeEntryDto> = emptyList(),
    val running: TimeEntryDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val activeProjects: List<ProjectDto> get() = projects.filter { !it.archived }
}

class TimeViewModel(
    private val repository: TimeRepository,
    private val token: String,
    private val username: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimeUiState(isLoading = true))
    val uiState: StateFlow<TimeUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val projects = repository.getProjects()
            val entries = repository.getEntries()
            val error = projects.exceptionOrNull()?.message ?: entries.exceptionOrNull()?.message
            _uiState.update { state ->
                val nextEntries = entries.getOrDefault(state.entries)
                state.copy(
                    projects = projects.getOrDefault(state.projects),
                    entries = nextEntries,
                    running = findRunning(nextEntries),
                    isLoading = false,
                    error = error,
                )
            }
        }
    }

    fun startTimer(projectId: String, description: String?) {
        viewModelScope.launch {
            repository.startTimer(projectId, description?.trim()?.takeIf { it.isNotEmpty() })
                .onSuccess { entry -> upsertEntry(entry) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun stopTimer() {
        viewModelScope.launch {
            repository.stopTimer()
                .onSuccess { entry -> upsertEntry(entry) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun addManualEntry(projectId: String, startedAt: String, stoppedAt: String, description: String?) {
        viewModelScope.launch {
            repository.createEntry(projectId, startedAt, stoppedAt, description?.trim()?.takeIf { it.isNotEmpty() })
                .onSuccess { entry -> upsertEntry(entry) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            repository.deleteEntry(id)
                .onSuccess { removeEntry(id) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun addProject(name: String, color: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createProject(name.trim(), color)
                .onSuccess { project ->
                    _uiState.update { state ->
                        if (state.projects.any { it.id == project.id }) state
                        else state.copy(projects = state.projects + project)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch {
            repository.setArchived(id, archived)
                .onSuccess { project ->
                    _uiState.update { state ->
                        state.copy(projects = state.projects.map { if (it.id == project.id) project else it })
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun upsertEntry(entry: TimeEntryDto) {
        _uiState.update { state ->
            val entries = if (state.entries.any { it.id == entry.id })
                state.entries.map { if (it.id == entry.id) entry else it }
            else listOf(entry) + state.entries
            state.copy(entries = entries, running = findRunning(entries))
        }
    }

    private fun removeEntry(id: String) {
        _uiState.update { state ->
            val entries = state.entries.filter { it.id != id }
            state.copy(entries = entries, running = findRunning(entries))
        }
    }

    private fun findRunning(entries: List<TimeEntryDto>): TimeEntryDto? =
        entries.firstOrNull { it.stoppedAt == null && (username == null || it.userId == username) }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is TimeWebSocketClient.WsEvent.ProjectCreated -> _uiState.update { state ->
                        if (state.projects.any { it.id == event.project.id }) state
                        else state.copy(projects = state.projects + event.project)
                    }
                    is TimeWebSocketClient.WsEvent.ProjectUpdated -> _uiState.update { state ->
                        state.copy(projects = state.projects.map { if (it.id == event.project.id) event.project else it })
                    }
                    is TimeWebSocketClient.WsEvent.EntryCreated -> upsertEntry(event.entry)
                    is TimeWebSocketClient.WsEvent.EntryUpdated -> upsertEntry(event.entry)
                    is TimeWebSocketClient.WsEvent.EntryDeleted -> removeEntry(event.entry.id)
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
