package com.homebase.android.ui.abwesenheit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.UpdateAbsSettingsRequest
import com.homebase.android.data.repository.AbsenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AbsenceUiState(
    val data: AbsenceStateDto = AbsenceStateDto(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Drives the Familienkalender. The backend is pure persistence and the planner
 * state is small, so every mutation re-reads the whole snapshot on success (and the
 * absence WebSocket triggers the same refetch when the other user makes a change).
 */
class AbsenceViewModel(
    private val repository: AbsenceRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AbsenceUiState())
    val uiState: StateFlow<AbsenceUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getState()
                .onSuccess { snapshot -> _uiState.update { it.copy(data = snapshot, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /** Silent refetch (no loading flicker) after a mutation or a WebSocket ping. */
    private fun refetch() {
        viewModelScope.launch {
            repository.getState().onSuccess { snapshot -> _uiState.update { it.copy(data = snapshot) } }
        }
    }

    private fun mutate(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block()
                .onSuccess { refetch() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    // --- day editor ---------------------------------------------------------

    fun setAbsence(userId: String, date: String, type: String, half: String?) =
        mutate { repository.setAbsence(userId, date, type, half) }

    fun clearAbsence(userId: String, date: String) =
        mutate { repository.clearAbsence(userId, date) }

    /** Apply [type] across a range; setting a type hits only this user's working days,
     *  clearing (type == null) wipes every date in the span. Mirrors the web range editor. */
    fun setAbsenceRange(userId: String, type: String?, from: String, to: String, half: String?) {
        val dates = if (type != null) {
            eachDate(from, to).filter { isWorkdayFor(_uiState.value.data, userId, it) }
        } else {
            eachDate(from, to)
        }
        mutate { repository.batchAbsence(userId, type, half, dates) }
    }

    // --- kita ---------------------------------------------------------------

    fun toggleKita(date: String, label: String?) {
        val existing = _uiState.value.data.kitaClosures.find { it.date == date }
        when {
            label == null -> if (existing != null) mutate { repository.removeKita(existing.id) }
            existing == null -> mutate { repository.addKita(date, label) }
        }
    }

    fun setKitaLabel(date: String, label: String) {
        val existing = _uiState.value.data.kitaClosures.find { it.date == date }
        if (existing != null) mutate { repository.updateKita(existing.id, null, label) }
        else mutate { repository.addKita(date, label) }
    }

    fun addKita(date: String, label: String) = mutate { repository.addKita(date, label) }
    fun addKitaRange(from: String, to: String, label: String) = mutate { repository.addKitaRange(from, to, label) }
    fun updateKita(id: String, date: String? = null, label: String? = null) = mutate { repository.updateKita(id, date, label) }
    fun removeKita(id: String) = mutate { repository.removeKita(id) }

    // --- settings & part-time ----------------------------------------------

    fun updateSettings(userId: String, year: Int, patch: UpdateAbsSettingsRequest) =
        mutate { repository.updateSettings(userId, year, patch) }

    fun addPartTime(userId: String, weekday: Int, start: String, end: String?) =
        mutate { repository.addPartTime(userId, weekday, start, end) }

    /** Update a rule from a partial patch, filling unset fields from the current rule. */
    fun updatePartTime(id: String, weekday: Int? = null, start: String? = null, end: String? = null) {
        val rule = _uiState.value.data.partTime.find { it.id == id } ?: return
        mutate { repository.updatePartTime(id, weekday ?: rule.weekday, start ?: rule.start, end ?: rule.end) }
    }

    fun removePartTime(id: String) = mutate { repository.removePartTime(id) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { refetch() }
        }
    }

    /** Reconnect the channel if it dropped — called from the UI when the app returns to the foreground. */
    fun ensureConnected() = repository.ensureWebSocketConnected()

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
