package com.homebase.android.ui.abwesenheit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.UpdateAbsSettingsRequest
import com.homebase.android.data.abwesenheit.AbsenceSnapshot
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.repository.AbsenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /**
     * Durable "last-known planner snapshot" cache (#520, read-side twin of the shopping cache #517).
     * Seeded on a cold start so a launch with no connection shows the previous planner instead of an
     * empty screen, and mirrored on every change. null in tests → no read-cache.
     */
    private val snapshotStore: SnapshotStore<AbsenceSnapshot>? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AbsenceUiState())
    val uiState: StateFlow<AbsenceUiState> = _uiState.asStateFlow()

    /** True once a fetch has successfully applied server data (#520); guards the cache seed against
     *  clobbering live data and gates the load error. Single-threaded (viewModelScope = Main). */
    private var hasServerData = false

    init {
        load()
        observeWebSocket()
        restoreAndMirrorSnapshot()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getState()
                .onSuccess { snapshot ->
                    hasServerData = true // a successful fetch landed → the cache seed must not clobber it (#520)
                    _uiState.update { it.copy(data = snapshot, isLoading = false) }
                }
                .onFailure { e ->
                    // Keep `error` only when there is nothing to show anyway (#520): with a cached/prior
                    // snapshot on screen a failed refresh stays silent — offline we show the old state.
                    _uiState.update { s -> s.copy(isLoading = false, error = if (s.data == AbsenceStateDto()) e.message else null) }
                }
        }
    }

    /**
     * Offline read-cache (#520): seed the last-known planner snapshot from disk before starting the
     * mirror collector (so the empty startup frame can't wipe a good cache), then persist every
     * distinct change. [hasServerData] + the default-equality guard keep a slow disk read from
     * clobbering fresh server data.
     */
    private fun restoreAndMirrorSnapshot() {
        val store = snapshotStore ?: return
        viewModelScope.launch {
            val cached = store.load()
            if (cached != null && !hasServerData && cached.data != AbsenceStateDto()) {
                _uiState.update { s ->
                    if (hasServerData || s.data != AbsenceStateDto()) s
                    else s.copy(data = cached.data, isLoading = false, error = null)
                }
            }
            uiState
                .map { it.data }
                .distinctUntilChanged()
                .collect { data -> store.save(AbsenceSnapshot(data = data)) }
        }
    }

    /**
     * Silent refetch (no loading flicker) after a mutation, a WebSocket ping, a (re)connect, or app
     * resume (#269). Leaves existing data + `error` untouched on a transient failure so a dropped
     * network never blanks the planner; the next trigger retries.
     */
    private fun refetch() {
        viewModelScope.launch {
            repository.getState().onSuccess { snapshot -> _uiState.update { it.copy(data = snapshot) } }
        }
    }

    /**
     * Pull-to-refresh entry point (#269). Suspends until the snapshot reload completes so the UI's
     * refresh indicator can spin for the duration; no full-screen spinner (the planner stays visible)
     * but it does surface a fetch error, since it's user-triggered.
     */
    suspend fun refresh() {
        repository.getState()
            .onSuccess { snapshot -> _uiState.update { it.copy(data = snapshot, error = null) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
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

    // --- custom holidays (#243, year-agnostic month+day) ---------------------

    fun addCustomHoliday(month: Int, day: Int, half: Boolean, label: String) =
        mutate { repository.addCustomHoliday(month, day, half, label) }

    /** Update a holiday from a partial patch; unset fields stay unchanged on the backend. */
    fun updateCustomHoliday(id: String, month: Int? = null, day: Int? = null, half: Boolean? = null, label: String? = null) =
        mutate { repository.updateCustomHoliday(id, month, day, half, label) }

    fun removeCustomHoliday(id: String) = mutate { repository.removeCustomHoliday(id) }

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
        // Re-read the snapshot on every (re)connect — the "server reachable again" signal (#269,
        // mirrors the time channel + shopping queue flush). The first connect also fires this; that
        // one refetch overlaps load()'s fetch (harmless — a cheap GET at cold start), and every later
        // reconnect then reliably re-syncs without bespoke state.
        repository.setWebSocketOnConnected { refetch() }
        viewModelScope.launch {
            repository.incomingEvents.collect { refetch() }
        }
    }

    /**
     * Called from the UI when the app returns to the foreground (#269). Reconnects the channel if it
     * dropped **and** re-reads the snapshot: a reconnect fires `onConnected` → [refetch], but if the
     * socket survived the background no callback fires, so we also refetch here. Either way the
     * planner matches the server after a backgrounded change elsewhere.
     */
    fun ensureConnected() {
        repository.ensureWebSocketConnected()
        refetch()
    }

    override fun onCleared() {
        super.onCleared()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }
}
