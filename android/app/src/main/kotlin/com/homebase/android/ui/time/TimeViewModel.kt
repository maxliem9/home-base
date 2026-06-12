package com.homebase.android.ui.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.TimeForecastDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** One changed Wochensoll cell to PUT (#55); null fields stay untouched server-side. */
data class TargetChange(
    val userId: String,
    val projectId: String,
    val weeklyHours: Double? = null,
    val isDefault: Boolean? = null,
)

data class TimeUiState(
    val projects: List<ProjectDto> = emptyList(),
    val entries: List<TimeEntryDto> = emptyList(),
    val running: TimeEntryDto? = null,
    // Live timers of the other household member(s) — partner strip.
    val othersRunning: List<TimeEntryDto> = emptyList(),
    // Household members' usernames — lets us offer "start a timer for the partner".
    val users: List<String> = emptyList(),
    // Wochensoll & Forecast (#31/#55) — non-critical reads, null/empty without targets.
    val forecast: TimeForecastDto? = null,
    // When the forecast snapshot was fetched — lets a running timer tick the displayed
    // Soll/Ist live instead of freezing it at fetch time (#64, web: forecastAtMs).
    val forecastAt: Instant? = null,
    val targets: List<WorkTargetDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val activeProjects: List<ProjectDto> get() = projects.filter { !it.archived }

    /** People with a configured weekly target — only they get a Wochenbilanz/ETA. */
    val weekUsers: List<UserForecastDto>
        get() = forecast?.users?.filter { it.weekTargetSeconds > 0 } ?: emptyList()

    /** Forecast of [userId], only when that person has a weekly target > 0. */
    fun forecastFor(userId: String?): UserForecastDto? =
        userId?.let { id -> weekUsers.firstOrNull { it.userId == id } }
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
            val users = repository.getUsers() // non-critical — only enables "start for partner"
            // Forecast + targets are non-critical reads (#31/#55): on failure the
            // Wochensoll UI simply stays hidden, the rest of the screen works.
            val forecast = repository.getForecast()
            val targets = repository.getTargets()
            val error = projects.exceptionOrNull()?.message ?: entries.exceptionOrNull()?.message
            _uiState.update { state ->
                val nextEntries = entries.getOrDefault(state.entries)
                state.copy(
                    projects = projects.getOrDefault(state.projects),
                    entries = nextEntries,
                    running = findRunning(nextEntries),
                    othersRunning = findOthersRunning(nextEntries),
                    users = users.getOrNull()?.map { it.username } ?: state.users,
                    forecast = forecast.getOrNull() ?: state.forecast,
                    forecastAt = if (forecast.isSuccess) Instant.now() else state.forecastAt,
                    targets = targets.getOrDefault(state.targets),
                    isLoading = false,
                    error = error,
                )
            }
        }
    }

    /** `userId` starts the timer on behalf of the partner; null → self. */
    fun startTimer(projectId: String, description: String?, userId: String? = null) {
        viewModelScope.launch {
            repository.startTimer(projectId, description?.trim()?.takeIf { it.isNotEmpty() }, userId)
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** `userId` stops the partner's timer; null → own timer. */
    fun stopTimer(userId: String? = null) {
        viewModelScope.launch {
            repository.stopTimer(userId)
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun addManualEntry(projectId: String, startedAt: String, stoppedAt: String, description: String?) {
        viewModelScope.launch {
            repository.createEntry(projectId, startedAt, stoppedAt, description?.trim()?.takeIf { it.isNotEmpty() })
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Edit an existing entry, or — for the running timer — just its start time.
     * Pass only the fields that should change (a null projectId/stoppedAt leaves
     * that column untouched on the backend), so editing a still-running entry sends
     * only startedAt and editing an archived-project entry need not resend the project.
     */
    fun updateEntry(id: String, request: UpdateTimeEntryRequest) {
        viewModelScope.launch {
            repository.updateEntry(id, request)
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Konnte nicht gespeichert werden.") } }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            repository.deleteEntry(id)
                .onSuccess { removeEntry(id); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Split a completed own entry at [splitAt] (#66): both halves come straight from
     * the response (no waiting for the WS echo), then the forecast is reloaded.
     */
    fun splitEntry(id: String, splitAt: String, breakMinutes: Int?) {
        viewModelScope.launch {
            repository.splitEntry(id, splitAt, breakMinutes)
                .onSuccess { halves ->
                    upsertEntry(halves.first)
                    upsertEntry(halves.second)
                    refreshForecast()
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Eintrag konnte nicht gesplittet werden.") } }
        }
    }

    /**
     * Save the changed Wochensoll cells (#55) — one PUT per change; userId is the
     * target person (household-shared like the absence planner). Afterwards targets
     * and forecast are refetched so the UI reflects the real server state even after
     * a partial failure.
     */
    fun saveTargets(changes: List<TargetChange>) {
        if (changes.isEmpty()) return
        viewModelScope.launch {
            var failed = false
            changes.forEach { c ->
                repository.upsertTarget(c.userId, c.projectId, c.weeklyHours, c.isDefault)
                    .onFailure { failed = true }
            }
            val targets = repository.getTargets()
            val forecast = repository.getForecast()
            _uiState.update { state ->
                state.copy(
                    targets = targets.getOrDefault(state.targets),
                    forecast = forecast.getOrNull() ?: state.forecast,
                    forecastAt = if (forecast.isSuccess) Instant.now() else state.forecastAt,
                    error = if (failed) "Wochensoll konnte nicht gespeichert werden." else state.error,
                )
            }
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
            state.copy(entries = entries, running = findRunning(entries), othersRunning = findOthersRunning(entries))
        }
    }

    private fun removeEntry(id: String) {
        _uiState.update { state ->
            val entries = state.entries.filter { it.id != id }
            state.copy(entries = entries, running = findRunning(entries), othersRunning = findOthersRunning(entries))
        }
    }

    private fun findRunning(entries: List<TimeEntryDto>): TimeEntryDto? =
        entries.firstOrNull { it.stoppedAt == null && (username == null || it.userId == username) }

    private fun findOthersRunning(entries: List<TimeEntryDto>): List<TimeEntryDto> =
        entries.filter { it.stoppedAt == null && username != null && it.userId != username }

    /** Refetch only the forecast — any entry change shifts recorded time / expected end. */
    private fun refreshForecast() {
        viewModelScope.launch {
            repository.getForecast().onSuccess { f ->
                _uiState.update { it.copy(forecast = f, forecastAt = Instant.now()) }
            }
        }
    }

    /**
     * Refetch the full target list. The TARGET_UPDATED frame carries only the changed
     * row, but setting a new default clears the old one server-side — refetching keeps
     * the local list consistent without mirroring that logic.
     */
    private fun refreshTargets() {
        viewModelScope.launch {
            repository.getTargets().onSuccess { t -> _uiState.update { it.copy(targets = t) } }
        }
    }

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
                    // any entry change shifts the forecast (recorded time, expected end)
                    is TimeWebSocketClient.WsEvent.EntryCreated -> { upsertEntry(event.entry); refreshForecast() }
                    is TimeWebSocketClient.WsEvent.EntryUpdated -> { upsertEntry(event.entry); refreshForecast() }
                    is TimeWebSocketClient.WsEvent.EntryDeleted -> { removeEntry(event.entry.id); refreshForecast() }
                    is TimeWebSocketClient.WsEvent.TargetUpdated -> { refreshTargets(); refreshForecast() }
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
