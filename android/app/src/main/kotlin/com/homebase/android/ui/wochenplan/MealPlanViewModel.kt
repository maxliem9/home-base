package com.homebase.android.ui.wochenplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.wochenplan.MealPlanSnapshot
import com.homebase.android.data.repository.MealPlanRepository
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

/** Grid meal slots, in meal order — independent of the recipe categories (#250). */
val MEAL_SLOTS = listOf("BREAKFAST", "LUNCH", "DINNER")

data class MealPlanUiState(
    val weekStart: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    val entries: List<MealPlanEntryDto> = emptyList(),
    val recipes: List<RecipeDto> = emptyList(),
    val shoppingLists: List<ShoppingListDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /** The 7 dates of the visible week (Mon..Sun). */
    val weekDates: List<LocalDate> get() = (0L..6L).map { weekStart.plusDays(it) }

    fun entryFor(date: String, slot: String): MealPlanEntryDto? =
        entries.firstOrNull { it.date == date && it.slot == slot }
}

class MealPlanViewModel(
    private val repository: MealPlanRepository,
    private val token: String,
    /**
     * Durable "last-known plan" cache (#520, read-side twin of the shopping cache #517). Seeded on a
     * cold start so a launch with no connection shows the previous plan instead of an empty grid, and
     * mirrored on every change. Entries are week-scoped (see [restoreAndMirrorSnapshot]); recipes +
     * shopping lists are week-independent. null in tests → no read-cache.
     */
    private val snapshotStore: SnapshotStore<MealPlanSnapshot>? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealPlanUiState(isLoading = true))
    val uiState: StateFlow<MealPlanUiState> = _uiState.asStateFlow()

    /** True once the entries fetch has successfully landed (#520); guards the week-scoped entries seed
     *  against clobbering live data and gates the load error. Single-threaded (viewModelScope = Main). */
    private var hasServerData = false

    init {
        loadRecipes()
        loadLists()
        loadEntries()
        observeWebSockets()
        restoreAndMirrorSnapshot()
    }

    fun loadEntries() {
        val start = _uiState.value.weekStart
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getMealPlan(start.toString(), start.plusDays(6).toString())
                .onSuccess { list ->
                    hasServerData = true // a successful fetch landed → the cache seed must not clobber it (#520)
                    _uiState.update { it.copy(entries = list, isLoading = false) }
                }
                .onFailure { e ->
                    // Keep `error` only when there is nothing to show anyway (#520): with cached/prior
                    // entries or recipes on screen a failed refresh stays silent — offline we show the old state.
                    _uiState.update { s -> s.copy(isLoading = false, error = if (s.entries.isEmpty() && s.recipes.isEmpty()) e.message else null) }
                }
        }
    }

    /**
     * Offline read-cache (#520): seed the last-known plan from disk before starting the mirror
     * collector (so the empty startup frame can't wipe a good cache), then persist every distinct
     * change. Entries are **week-scoped**, so they are only seeded when the cached week equals the
     * currently-visible week; recipes + shopping lists (week-independent) seed whenever still empty.
     * [hasServerData]/`ifEmpty` guard against clobbering fresh server data.
     */
    private fun restoreAndMirrorSnapshot() {
        val store = snapshotStore ?: return
        viewModelScope.launch {
            val cached = store.load()
            if (cached != null && !hasServerData) {
                _uiState.update { s ->
                    if (hasServerData) s
                    else s.copy(
                        recipes = s.recipes.ifEmpty { cached.recipes },
                        shoppingLists = s.shoppingLists.ifEmpty { cached.shoppingLists },
                        // Entries are week-specific: only restore them for the week we're actually showing.
                        entries = if (s.entries.isEmpty() && cached.weekStart == s.weekStart.toString()) cached.entries else s.entries,
                        isLoading = false,
                        error = null,
                    )
                }
            }
            uiState
                .map { MealPlanSnapshot(it.weekStart.toString(), it.entries, it.recipes, it.shoppingLists) }
                .distinctUntilChanged()
                .collect { snapshot -> store.save(snapshot) }
        }
    }

    private fun loadRecipes() {
        viewModelScope.launch {
            repository.getRecipes().onSuccess { r -> _uiState.update { it.copy(recipes = r) } }
        }
    }

    private fun loadLists() {
        viewModelScope.launch {
            repository.getShoppingLists().onSuccess { l -> _uiState.update { it.copy(shoppingLists = l) } }
        }
    }

    fun prevWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.minusWeeks(1)) }
        loadEntries()
    }

    fun nextWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.plusWeeks(1)) }
        loadEntries()
    }

    fun goToday() {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (!monday.isEqual(_uiState.value.weekStart)) {
            _uiState.update { it.copy(weekStart = monday) }
            loadEntries()
        }
    }

    /** Plan EITHER a recipe (recipeId + optional servings) OR a free-text dish (dishTitle), #293. */
    fun setSlot(date: String, slot: String, recipeId: String?, dishTitle: String?, servings: Int?) {
        viewModelScope.launch {
            repository.setMealSlot(date, slot, recipeId, dishTitle, servings)
                .onSuccess { entry ->
                    _uiState.update { s ->
                        s.copy(entries = s.entries.filterNot { it.date == entry.date && it.slot == entry.slot } + entry)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearSlot(date: String, slot: String) {
        val removed = _uiState.value.entryFor(date, slot)
        // optimistic remove
        _uiState.update { s -> s.copy(entries = s.entries.filterNot { it.date == date && it.slot == slot }) }
        viewModelScope.launch {
            repository.deleteMealSlot(date, slot).onFailure { e ->
                // Put the entry back and surface the error in one update — a loadEntries() reload here
                // would reset error to null first, so the user would never see it. The next WS push /
                // week reload reconciles the true server state anyway.
                _uiState.update { s ->
                    val stillGone = s.entries.none { it.date == date && it.slot == slot }
                    s.copy(
                        entries = if (removed != null && stillGone) s.entries + removed else s.entries,
                        error = e.message,
                    )
                }
            }
        }
    }

    /**
     * Aggregate the week's planned-recipe ingredients, scaled to each entry's chosen portions (#261;
     * the batch endpoint sums by name+unit), and add them to [listId]; reports (added, merged) to
     * [onResult] for the toast. An entry without an explicit servings keeps the recipe's own servings
     * (factor 1, 1× as authored). Free-text entries (#293) have no recipe/ingredients — skipped.
     */
    fun addWeekToShopping(listId: String, onResult: (added: Int, merged: Int) -> Unit) {
        val state = _uiState.value
        val byId = state.recipes.associateBy { it.id }
        val lines = state.entries.flatMap { e ->
            val recipe = e.recipeId?.let { byId[it] } ?: return@flatMap emptyList()
            val base = if (recipe.servings > 0) recipe.servings else 1
            val factor = (e.servings ?: base).toDouble() / base
            recipe.ingredients.map { ing ->
                val amount = ing.amount?.let { Math.round(it * factor * 1000.0) / 1000.0 }
                ShoppingLineInput(name = ing.name, amount = amount, unit = ing.unit)
            }
        }
        if (lines.isEmpty()) return
        viewModelScope.launch {
            repository.addToShopping(listId, lines)
                .onSuccess { res -> onResult(res.added, res.merged) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeWebSockets() {
        repository.connectWebSocket(token)
        viewModelScope.launch { repository.mealPlanEvents.collect { loadEntries() } }
        // A recipe rename/delete changes what the grid shows (delete cascades plan rows server-side
        // but only broadcasts on the recipes channel) — reload both recipes and the visible week.
        viewModelScope.launch {
            repository.recipeEvents.collect {
                loadRecipes()
                loadEntries()
            }
        }
    }

    fun ensureConnected() = repository.ensureWebSocketConnected()

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
