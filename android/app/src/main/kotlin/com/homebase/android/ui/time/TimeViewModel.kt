package com.homebase.android.ui.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeCreditDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.TimeForecastDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.time.TimeSnapshot
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One changed Wochensoll cell to PUT (#55); null fields stay untouched server-side. */
data class TargetChange(
    val userId: String,
    val projectId: String,
    val weeklyHours: Double? = null,
    val isDefault: Boolean? = null,
    // Wochensoll period this change lands in (null → base period, #31 follow-up).
    val validFrom: String? = null,
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
    // Absence/holiday work credits over the tracked-entry span (#31) — the Projekt-Detail
    // per-week list folds these in. Non-critical read; not persisted offline (like the forecast).
    val credits: List<TimeCreditDto> = emptyList(),
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
    /**
     * Durable "last-known time data" cache (#520, read-side twin of the shopping cache #517). Seeded
     * on a cold start so a launch with no connection shows the previous entries instead of nothing,
     * and mirrored on every change. null in tests → no read-cache.
     */
    private val snapshotStore: SnapshotStore<TimeSnapshot>? = null,
    // Resolves a repository AppError (carried by ApiException) to localized text via strings.xml (#558).
    // Default keeps the raw exception message (for tests); MainActivity injects the Context-backed one.
    private val errorText: (Throwable) -> String? = { it.message },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimeUiState(isLoading = true))
    val uiState: StateFlow<TimeUiState> = _uiState.asStateFlow()

    /** True once the critical fetch (projects + entries) has landed (#520); guards the cache seed
     *  against clobbering live data and gates the load error. Single-threaded (viewModelScope = Main). */
    private var hasServerData = false

    init {
        load()
        observeWebSocket()
        restoreAndMirrorSnapshot()
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
            // Credits span the loaded entries (#31); skipped when entries failed to load.
            val credits = entries.getOrNull()?.let { fetchCreditsFor(it) }
            val error = projects.exceptionOrNull()?.let(errorText) ?: entries.exceptionOrNull()?.let(errorText)
            if (error == null) hasServerData = true // critical reads landed → the cache seed must not clobber (#520)
            _uiState.update { state ->
                val nextProjects = projects.getOrDefault(state.projects)
                val nextEntries = entries.getOrDefault(state.entries)
                state.copy(
                    projects = nextProjects,
                    entries = nextEntries,
                    running = findRunning(nextEntries),
                    othersRunning = findOthersRunning(nextEntries),
                    users = users.getOrNull()?.map { it.username } ?: state.users,
                    forecast = forecast.getOrNull() ?: state.forecast,
                    forecastAt = if (forecast.isSuccess) Instant.now() else state.forecastAt,
                    targets = targets.getOrDefault(state.targets),
                    credits = credits ?: state.credits,
                    isLoading = false,
                    // Keep `error` only when there is nothing to show anyway (#520): with cached/prior
                    // data on screen a failed refresh stays silent — offline we show the old state.
                    error = error?.takeIf { nextProjects.isEmpty() && nextEntries.isEmpty() },
                )
            }
        }
    }

    /**
     * Offline read-cache (#520): seed the last-known time datasets from disk before starting the
     * mirror collector (so the empty startup frame can't wipe a good cache), then persist every
     * distinct change. The derived running/others timers are recomputed from the seeded [entries].
     * [hasServerData]/`ifEmpty` guard against clobbering fresh server data.
     */
    private fun restoreAndMirrorSnapshot() {
        val store = snapshotStore ?: return
        viewModelScope.launch {
            val cached = store.load()
            if (cached != null && !hasServerData && (cached.entries.isNotEmpty() || cached.projects.isNotEmpty())) {
                _uiState.update { s ->
                    if (hasServerData) s
                    else {
                        val entries = s.entries.ifEmpty { cached.entries }
                        s.copy(
                            projects = s.projects.ifEmpty { cached.projects },
                            entries = entries,
                            running = s.running ?: findRunning(entries),
                            othersRunning = s.othersRunning.ifEmpty { findOthersRunning(entries) },
                            users = s.users.ifEmpty { cached.users },
                            forecast = s.forecast ?: cached.forecast,
                            targets = s.targets.ifEmpty { cached.targets },
                            isLoading = false,
                            error = null,
                        )
                    }
                }
            }
            uiState
                .map { TimeSnapshot(it.projects, it.entries, it.users, it.forecast, it.targets) }
                .distinctUntilChanged()
                .collect { snapshot -> store.save(snapshot) }
        }
    }

    /** `userId` starts the timer on behalf of the partner; null → self. */
    fun startTimer(projectId: String, description: String?, userId: String? = null) {
        viewModelScope.launch {
            repository.startTimer(projectId, description?.trim()?.takeIf { it.isNotEmpty() }, userId)
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /** `userId` stops the partner's timer; null → own timer. */
    fun stopTimer(userId: String? = null) {
        viewModelScope.launch {
            repository.stopTimer(userId)
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /** `userId` records the entry for the partner (shared household); null → self. */
    fun addManualEntry(projectId: String, startedAt: String, stoppedAt: String, description: String?, userId: String? = null) {
        viewModelScope.launch {
            repository.createEntry(projectId, startedAt, stoppedAt, description?.trim()?.takeIf { it.isNotEmpty() }, userId)
                .onSuccess { entry -> upsertEntry(entry); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            repository.deleteEntry(id)
                .onSuccess { removeEntry(id); refreshForecast() }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /**
     * Split an entry — completed or running (#634) — at [splitAt] (#66): both halves come straight from
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
                repository.upsertTarget(c.userId, c.projectId, c.weeklyHours, c.isDefault, c.validFrom)
                    .onFailure { failed = true }
            }
            refreshTargetsAndForecast(failed)
        }
    }

    /**
     * Schedule a new Wochensoll period across the household (#31 follow-up): one POST per
     * person so their targets stay aligned; each is seeded server-side from the effective
     * values. A 409 (this person already has the period) is tolerated. Then refetch.
     */
    fun createTargetPeriod(userIds: List<String>, validFrom: String) {
        viewModelScope.launch {
            var failed = false
            userIds.forEach { userId ->
                repository.createTargetPeriod(userId, validFrom).onFailure { failed = true }
            }
            refreshTargetsAndForecast(failed)
        }
    }

    /** Delete a Wochensoll period across the household; a 404 (no such period) is tolerated. */
    fun deleteTargetPeriod(userIds: List<String>, validFrom: String) {
        viewModelScope.launch {
            var failed = false
            userIds.forEach { userId ->
                repository.deleteTargetPeriod(userId, validFrom).onFailure { failed = true }
            }
            refreshTargetsAndForecast(failed)
        }
    }

    /** Refetch targets + forecast after a mutation so the UI reflects real server state. */
    private suspend fun refreshTargetsAndForecast(failed: Boolean) {
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /** Rename / recolour a project (#175). The returned row replaces the local one in place. */
    fun updateProject(id: String, name: String, color: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.updateProject(id, name.trim(), color)
                .onSuccess { project ->
                    _uiState.update { state ->
                        state.copy(projects = state.projects.map { if (it.id == project.id) project else it })
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /**
     * Fetch the CSV export bytes (optional date-range/project filter) and hand the
     * result to [onResult] (#175). The screen turns success into a file + share-sheet
     * — it owns the Android Context, like the recipe export.
     */
    fun exportCsv(
        from: String?,
        to: String?,
        projectId: String?,
        onResult: (Result<ByteArray>) -> Unit,
    ) {
        viewModelScope.launch { onResult(repository.exportCsv(from, to, projectId)) }
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

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * Absence/holiday credits (#31) over the tracked-entry span (earliest entry day → today),
     * for the Projekt-Detail per-week list. No entries → empty span → empty list. Best-effort
     * (mirrors the web credit fetch): a failure keeps whatever we already have.
     */
    private suspend fun fetchCreditsFor(entries: List<TimeEntryDto>): List<TimeCreditDto>? {
        val from = entries
            .mapNotNull { runCatching { Instant.parse(it.startedAt).atZone(zone).toLocalDate() }.getOrNull() }
            .minOrNull() ?: return emptyList()
        return repository.getCredits(from.toString(), LocalDate.now(zone).toString()).getOrNull()
    }

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

    /**
     * Silent background re-sync of entries + forecast (#268). Fires on every WS (re)connect
     * (`onConnected`) and on app/screen resume ([ensureConnected]). A timer stopped on the web
     * or another device while our socket was dead (Doze / mobile-network change / backend restart)
     * sends an ENTRY_UPDATED we never receive — without this refetch the stale "running" timer
     * (derived from the open entry) would stay visibly on until the next logout/login. Refetching
     * the entry list re-derives `running`/`othersRunning`, so a now-stopped entry clears itself.
     *
     * Unlike [load] this keeps `isLoading` false (no full-screen spinner for a background sync) and
     * leaves existing state untouched on a transient failure — the next trigger retries.
     */
    private fun syncFromServer() {
        viewModelScope.launch {
            val entries = repository.getEntries()
            val forecast = repository.getForecast()
            val credits = entries.getOrNull()?.let { fetchCreditsFor(it) }
            _uiState.update { state ->
                val nextEntries = entries.getOrDefault(state.entries)
                state.copy(
                    entries = nextEntries,
                    running = findRunning(nextEntries),
                    othersRunning = findOthersRunning(nextEntries),
                    forecast = forecast.getOrNull() ?: state.forecast,
                    forecastAt = if (forecast.isSuccess) Instant.now() else state.forecastAt,
                    credits = credits ?: state.credits,
                )
            }
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        // Re-sync on every (re)connect — the "server reachable again" signal, the mobile analog
        // of the web's WS onOpen (#268, mirrors the shopping offline-queue flush). The first connect
        // also fires this; that one re-sync overlaps load()'s fetch (harmless — two cheap GETs at
        // cold start), and in return every later reconnect reliably re-syncs without bespoke state.
        repository.setWebSocketOnConnected { syncFromServer() }
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

    /**
     * Called from the UI when the app returns to the foreground. Reconnects the channel if it dropped
     * **and** re-syncs from the server (#268): a reconnect fires `onConnected` → [syncFromServer], but
     * if the socket survived the background no callback fires, so we also refetch here. Either way the
     * running timer matches the server — a stop done elsewhere while we were backgrounded clears.
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
