package com.homebase.android.ui.familienkalender

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.BuildConfig
import com.homebase.android.data.model.AbsenceDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.CalendarFeedConfigResponse
import com.homebase.android.data.model.KitaClosureDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.familienkalender.CalendarSnapshot
import com.homebase.android.data.repository.CalendarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Stable meal-slot order within a day (independent of recipe categories, like the web view). */
private val MEAL_SLOT_ORDER = mapOf("BREAKFAST" to 0, "LUNCH" to 1, "DINNER" to 2)

/** Everything happening on one day, already grouped by domain (mirrors the web DayBucket). */
data class DayBucket(
    val events: List<CalendarEventDto> = emptyList(),
    val absences: List<AbsenceDto> = emptyList(),
    val kita: KitaClosureDto? = null,
    val todos: List<TodoDto> = emptyList(),
    val meals: List<MealPlanEntryDto> = emptyList(),
) {
    val isEmpty: Boolean
        get() = events.isEmpty() && absences.isEmpty() && kita == null && todos.isEmpty() && meals.isEmpty()
}

data class FamilienkalenderUiState(
    /** The visible month, anchored to its first day. */
    val monthAnchor: LocalDate = LocalDate.now().withDayOfMonth(1),
    val todos: List<TodoDto> = emptyList(),
    val absences: List<AbsenceDto> = emptyList(),
    val kitaClosures: List<KitaClosureDto> = emptyList(),
    val meals: List<MealPlanEntryDto> = emptyList(),
    val events: List<CalendarEventDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /**
     * The grid spans whole Mon..Sun weeks around the month so it always renders a clean rectangle.
     * A fully-trailing 6th week that belongs entirely to the next month is trimmed (keeps it tight).
     */
    val gridDays: List<LocalDate>
        get() {
            val first = monthAnchor.with(TemporalAdjusters.firstDayOfMonth())
            val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val raw = (0L until 42L).map { start.plusDays(it) }
            return raw.chunked(7)
                .filter { week -> week.any { it.monthValue == monthAnchor.monthValue && it.year == monthAnchor.year } }
                .flatten()
        }

    /** Index everything by ISO date (YYYY-MM-DD) for O(1) cell lookups. */
    val buckets: Map<String, DayBucket>
        get() {
            val map = HashMap<String, MutableDayBucket>()
            fun ensure(date: String) = map.getOrPut(date) { MutableDayBucket() }
            for (t in todos) {
                val due = t.dueDate ?: continue
                if (t.status == "DONE") continue
                ensure(due).todos.add(t)
            }
            for (a in absences) ensure(a.date).absences.add(a)
            for (k in kitaClosures) ensure(k.date).kita = k
            for (m in meals) ensure(m.date).meals.add(m)
            for (e in events) ensure(e.date).events.add(e)
            return map.mapValues { (_, b) ->
                DayBucket(
                    events = b.events.sortedBy { eventSortKey(it) },
                    absences = b.absences,
                    kita = b.kita,
                    todos = b.todos,
                    meals = b.meals.sortedBy { MEAL_SLOT_ORDER[it.slot] ?: 9 },
                )
            }
        }
}

/** All-day events sort first (empty key), then timed ones by start time. */
private fun eventSortKey(e: CalendarEventDto): String =
    if (e.allDay || e.startTime.isNullOrBlank()) "" else e.startTime

private class MutableDayBucket {
    val events = mutableListOf<CalendarEventDto>()
    val absences = mutableListOf<AbsenceDto>()
    var kita: KitaClosureDto? = null
    val todos = mutableListOf<TodoDto>()
    val meals = mutableListOf<MealPlanEntryDto>()
}

class FamilienkalenderViewModel(
    private val repository: CalendarRepository,
    private val token: String,
    /**
     * Durable "last-known overlay" cache (#520, read-side twin of the shopping cache #517). Seeded on a
     * cold start so a launch with no connection shows the previous month instead of an empty grid, and
     * mirrored on every change. Meals + events are month-scoped (see [restoreAndMirrorSnapshot]). null
     * in tests → no read-cache.
     */
    private val snapshotStore: SnapshotStore<CalendarSnapshot>? = null,
    // Resolves a repository AppError (carried by ApiException) to localized text via strings.xml (#558).
    // Default keeps the raw exception message (for tests); MainActivity injects the Context-backed one.
    private val errorText: (Throwable) -> String? = { it.message },
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilienkalenderUiState(isLoading = true))
    val uiState: StateFlow<FamilienkalenderUiState> = _uiState.asStateFlow()

    /** True once a fetch has successfully applied server data (#520); guards the cache seed against
     *  clobbering live data and gates the load error. Single-threaded (viewModelScope = Main). */
    private var hasServerData = false

    /**
     * The caller's personal iCal subscription URL (#488), mirroring the web SubscribeModal. The JWT
     * rides in the query string — calendar apps (Apple/Google) can set neither an Authorization
     * header nor a WS subprotocol, so the backend accepts `?token=` here (same path as note images).
     * BuildConfig.BASE_URL already ends with `…/api/v1/`; a JWT is URL-safe (base64url + dots), so no
     * percent-encoding is needed. The token is personal — the UI warns not to share the link.
     */
    val feedUrl: String = BuildConfig.BASE_URL.trimEnd('/') + "/calendar.ics?token=" + token

    /** Load the caller's feed category selection + the full available list (for the subscribe sheet). */
    suspend fun loadFeedConfig(): Result<CalendarFeedConfigResponse> = repository.getCalendarFeedConfig()

    /** Persist the caller's feed category selection (the full set; empty = nothing). */
    suspend fun saveFeedConfig(sections: List<String>): Result<CalendarFeedConfigResponse> =
        repository.updateCalendarFeedConfig(sections)

    // The currently in-flight load, cancelled when a newer one starts (rapid month nav / WS bursts).
    private var loadJob: Job? = null

    init {
        load()
        observeWebSockets()
        restoreAndMirrorSnapshot()
    }

    /**
     * Offline read-cache (#520): seed the last-known overlay from disk before starting the mirror
     * collector (so the empty startup frame can't wipe a good cache), then persist every distinct
     * change. Meals + events are **month-scoped**, so they are only seeded when the cached month equals
     * the currently-visible one; todos + absence-derived lists (date-bucketed, not month-scoped) seed
     * whenever still empty. [hasServerData]/`ifEmpty` guard against clobbering fresh server data.
     */
    private fun restoreAndMirrorSnapshot() {
        val store = snapshotStore ?: return
        viewModelScope.launch {
            val cached = store.load()
            if (cached != null && !hasServerData) {
                _uiState.update { s ->
                    if (hasServerData) s
                    else s.copy(
                        todos = s.todos.ifEmpty { cached.todos },
                        absences = s.absences.ifEmpty { cached.absences },
                        kitaClosures = s.kitaClosures.ifEmpty { cached.kitaClosures },
                        // Meals + events are month-specific: only restore them for the visible month.
                        meals = if (s.meals.isEmpty() && cached.monthAnchor == s.monthAnchor.toString()) cached.meals else s.meals,
                        events = if (s.events.isEmpty() && cached.monthAnchor == s.monthAnchor.toString()) cached.events else s.events,
                        isLoading = false,
                        error = null,
                    )
                }
            }
            uiState
                .map { CalendarSnapshot(it.monthAnchor.toString(), it.todos, it.absences, it.kitaClosures, it.meals, it.events) }
                .distinctUntilChanged()
                .collect { snapshot -> store.save(snapshot) }
        }
    }

    /** Reload everything the visible month grid needs. Meals + events are range-scoped to the grid. */
    fun load() {
        val anchor = _uiState.value.monthAnchor
        val days = _uiState.value.gridDays
        val from = days.first().toString()
        val to = days.last().toString()
        // Cancel any in-flight load so a slower older-month response can't land after a newer one.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val todos = repository.getTodos()
            val absence = repository.getAbsenceState()
            val meals = repository.getMealPlan(from, to)
            val events = repository.getEvents(from, to)
            // Belt-and-braces against out-of-order completion: if the user navigated to a different
            // month while this was in flight, drop the result (the newer load owns the state now).
            if (!_uiState.value.monthAnchor.isEqual(anchor)) return@launch
            val error = listOf(todos, absence, meals, events).firstNotNullOfOrNull { it.exceptionOrNull()?.let(errorText) }
            if (error == null) hasServerData = true // a successful fetch landed → the cache seed must not clobber it (#520)
            _uiState.update { s ->
                val nextTodos = todos.getOrNull() ?: s.todos
                val nextAbsences = absence.getOrNull()?.absences ?: s.absences
                val nextKita = absence.getOrNull()?.kitaClosures ?: s.kitaClosures
                val nextMeals = meals.getOrNull() ?: s.meals
                val nextEvents = events.getOrNull() ?: s.events
                s.copy(
                    todos = nextTodos,
                    absences = nextAbsences,
                    kitaClosures = nextKita,
                    meals = nextMeals,
                    events = nextEvents,
                    isLoading = false,
                    // Keep `error` only when there is nothing to show anyway (#520): with cached/prior
                    // data on the grid a failed refresh stays silent — offline we show the old month.
                    error = error?.takeIf {
                        nextTodos.isEmpty() && nextAbsences.isEmpty() && nextKita.isEmpty() && nextMeals.isEmpty() && nextEvents.isEmpty()
                    },
                )
            }
        }
    }

    fun prevMonth() {
        _uiState.update { it.copy(monthAnchor = it.monthAnchor.minusMonths(1)) }
        load()
    }

    fun nextMonth() {
        _uiState.update { it.copy(monthAnchor = it.monthAnchor.plusMonths(1)) }
        load()
    }

    fun goToday() {
        val anchor = LocalDate.now().withDayOfMonth(1)
        if (!anchor.isEqual(_uiState.value.monthAnchor)) {
            _uiState.update { it.copy(monthAnchor = anchor) }
            load()
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeWebSockets() {
        repository.connectWebSocket(token)
        // Every channel just triggers a reload of the visible month (payloads are small). recipes is
        // included because a recipe delete cascades meal-plan rows but only broadcasts there.
        viewModelScope.launch { repository.todoEvents.collect { load() } }
        viewModelScope.launch { repository.absenceEvents.collect { load() } }
        viewModelScope.launch { repository.mealPlanEvents.collect { load() } }
        viewModelScope.launch { repository.recipeEvents.collect { load() } }
        viewModelScope.launch { repository.eventEvents.collect { load() } }
    }

    fun ensureConnected() = repository.ensureWebSocketConnected()

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
