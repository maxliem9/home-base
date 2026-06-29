package com.homebase.android.ui.familienkalender

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.AbsenceDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.KitaClosureDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.repository.CalendarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilienkalenderUiState(isLoading = true))
    val uiState: StateFlow<FamilienkalenderUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSockets()
    }

    /** Reload everything the visible month grid needs. Meals + events are range-scoped to the grid. */
    fun load() {
        val days = _uiState.value.gridDays
        val from = days.first().toString()
        val to = days.last().toString()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val todos = repository.getTodos()
            val absence = repository.getAbsenceState()
            val meals = repository.getMealPlan(from, to)
            val events = repository.getEvents(from, to)
            _uiState.update { s ->
                s.copy(
                    todos = todos.getOrNull() ?: s.todos,
                    absences = absence.getOrNull()?.absences ?: s.absences,
                    kitaClosures = absence.getOrNull()?.kitaClosures ?: s.kitaClosures,
                    meals = meals.getOrNull() ?: s.meals,
                    events = events.getOrNull() ?: s.events,
                    isLoading = false,
                    error = listOf(todos, absence, meals, events).firstNotNullOfOrNull { it.exceptionOrNull()?.message },
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
