package com.homebase.android.ui.aufgaben

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.SubtaskDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateSubtaskRequest
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sentinel tab id for the built-in Inbox tab (issue #77). Real list ids are
 * UUIDs, so this can never collide — mirrors the web's `INBOX_ID`.
 */
const val INBOX_TAB_ID = "__inbox__"

/**
 * Sentinel ids for the cross-list "smart" tabs (#255/#256, mirrors web's
 * `ALL_ID`/`TODAY_ID`/`TOMORROW_ID`/`DONE_ID`). Like the Inbox they span every
 * list and are reachable from the dashboard stat tiles. UUID list ids never collide.
 */
const val ALL_TAB_ID = "__all__"
const val OVERDUE_TAB_ID = "__overdue__"
const val TODAY_TAB_ID = "__today__"
const val TOMORROW_TAB_ID = "__tomorrow__"
const val DONE_TAB_ID = "__done__"

/** All virtual (non-list) tab ids — the cross-list views that show origin-list meta and no quick-add. */
private val SMART_TAB_IDS = setOf(ALL_TAB_ID, OVERDUE_TAB_ID, TODAY_TAB_ID, TOMORROW_TAB_ID, DONE_TAB_ID)
private fun isVirtualTab(id: String?): Boolean = id == INBOX_TAB_ID || id in SMART_TAB_IDS

/**
 * Done todos in the cross-list smart-views (the "Alle" done-section and the
 * "Erledigt" tab) are limited to the last N calendar days so the section can't
 * grow unbounded across the whole history (#263). One shared window: it caps the
 * "Alle"/list done-sections AND widens "Erledigt" beyond just today. The
 * badge/tile COUNTS stay deliberately on "today" (doneTodayCount), untouched.
 *
 * N is now household-configurable in-app (#356, app_settings 'done_window_days'),
 * fetched by the ViewModel into [TodoUiState.doneWindowDays]; this constant is the
 * fallback used before that GET lands (and by HeuteScreen, which has no config read).
 * The per-device "Alle anzeigen" toggle (#340) can still lift the cap entirely.
 */
const val DONE_WINDOW_DAYS = 14

/**
 * Deep-link target the dashboard stat tiles can ask the tasks view to open
 * (#255/#256, mirrors web's `TodosFocus`). Maps 1:1 onto a tab sentinel id.
 */
enum class TodosFocus(val tabId: String) {
    INBOX(INBOX_TAB_ID),
    ALL(ALL_TAB_ID),
    OVERDUE(OVERDUE_TAB_ID),
    TODAY(TODAY_TAB_ID),
    TOMORROW(TOMORROW_TAB_ID),
    DONE(DONE_TAB_ID),
}

data class TodoUiState(
    val lists: List<TodoListDto> = emptyList(),
    val todos: List<TodoDto> = emptyList(),
    val activeListId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * "Alle anzeigen" für die Erledigt-Historie (#340): false = die letzten N Tage
     * (DONE_WINDOW_DAYS, Default), true = die ganze Historie. Betrifft nur die
     * angezeigten DONE-Inhalte (Erledigt-Tab + Done-Section); die Zählungen
     * (doneTodayCount …) bleiben bewusst auf "heute".
     *
     * Bewusst nur In-Memory (kein DataStore/SharedPreferences, anders als der Web-Toggle
     * in localStorage homebase_todos_done_show_all): die „Erledigt"-Historie ist ein
     * seltener Browse-Vorgang, der Toggle darf pro Session zurücksetzen.
     * Maintainer-Entscheidung (Session zu #340).
     */
    val doneShowAll: Boolean = false,
    /**
     * Household-configured "Erledigt"-window length in calendar days (#356, app_settings
     * `done_window_days`, default [DONE_WINDOW_DAYS] = 14). Applied to the Erledigt tab + the
     * done-sections; the per-device [doneShowAll] toggle still overrides it, and the COUNTS
     * (doneTodayCount …) stay on "today". Replaced by the fetched value once it loads.
     */
    val doneWindowDays: Int = DONE_WINDOW_DAYS,
) {
    /**
     * Whether the Inbox tab is active — either explicitly selected, or as the
     * default tab when no lists exist yet (#77, same rule as the web TodosView).
     * A smart tab being active keeps the Inbox inactive even without lists.
     */
    val inboxActive: Boolean get() = activeListId == INBOX_TAB_ID || (lists.isEmpty() && activeListId !in SMART_TAB_IDS)

    /** Which cross-list smart view is active, if any (#256). Null = Inbox or a real list. */
    val smartTab: TodosFocus? get() = TodosFocus.entries.firstOrNull { it != TodosFocus.INBOX && it.tabId == activeListId }

    /** Any cross-list view (Inbox + smart tabs): rows show origin-list meta and there's no quick-add target. */
    val crossListActive: Boolean get() = inboxActive || smartTab != null

    /** The selected list, falling back to the first one; null while a cross-list view is active. */
    val activeList: TodoListDto?
        get() = if (crossListActive) null else lists.firstOrNull { it.id == activeListId } ?: lists.firstOrNull()

    /**
     * Todos shown for the active tab. Inbox = alles Unverplante (#71/#77): status-INBOX
     * todos — auch wenn sie schon in einer Liste liegen — plus alle listen-losen Todos
     * unabhängig vom Status, damit nichts unerreichbar wird. `listId` kann im JSON ganz
     * fehlen (encodeDefaults=false, #46) und ist dann hier null. Smart-Tabs (#256) spannen
     * alle Listen: Alle = alle Todos, Heute/Morgen = offene mit Fälligkeit heute/morgen,
     * Erledigt = abgehakte der letzten N Tage (#263). Listen-Tabs zeigen exakt ihre eigenen Todos.
     */
    val visibleTodos: List<TodoDto>
        get() = when {
            inboxActive -> todos.filter { it.status == "INBOX" || it.listId == null }
            smartTab == TodosFocus.ALL -> todos
            smartTab == TodosFocus.OVERDUE -> todos.filter(::isOverdue)
            smartTab == TodosFocus.TODAY -> todos.filter(::isDueToday)
            smartTab == TodosFocus.TOMORROW -> todos.filter(::isDueTomorrow)
            // "Erledigt"-Tab: über alle Listen, letzte N Tage (#263, N konfigurierbar #356) bzw.
            // die ganze Historie bei "Alle anzeigen" (#340). Die Tab-/Kachel-Zählung bleibt "heute".
            smartTab == TodosFocus.DONE -> todos.filter { isDoneShown(it, doneShowAll, doneWindowDays) }
            else -> activeList?.id?.let { id -> todos.filter { it.listId == id } } ?: emptyList()
        }

    /** Inbox tab badge: number of status-INBOX todos — same rule as the HeuteScreen tile (#71). */
    val inboxCount: Int get() = todos.count { it.status == "INBOX" }

    /** "Alle" tab badge: open todos across every list — mirrors the dashboard exactly (#256). */
    val allOpenCount: Int get() = todos.count { it.status != "DONE" }

    /** "Überfällig" tab badge: open, overdue todos across every list — mirrors the dashboard tile. */
    val overdueCount: Int get() = todos.count(::isOverdue)

    /** "Heute" tab badge: open, due-today todos across every list — mirrors the dashboard tile (#256). */
    val todayCount: Int get() = todos.count(::isDueToday)

    /** "Morgen" tab badge: open, due-tomorrow todos across every list — mirrors the dashboard tile (#256). */
    val tomorrowCount: Int get() = todos.count(::isDueTomorrow)

    /** "Erledigt" tab badge: todos completed today across every list — mirrors the dashboard tile (#256). */
    val doneTodayCount: Int get() = todos.count(::isDoneToday)

    /** Count of open (not done) todos across all lists — used for the drawer badge. */
    val openCount: Int get() = todos.count { it.status != "DONE" }
}

// --- Cross-list smart-view predicates (#256/#263) — local-date semantics in the device zone,
// mirroring the web (dueLabel tone / localDateIso). Shared by TodoUiState above and HeuteScreen. ---

/** Open todo whose due date is in the past (the dashboard "Überfällig" rule). */
internal fun isOverdue(t: TodoDto): Boolean =
    t.status != "DONE" && Format.dueGroup(t.dueDate) == Format.DueGroup.UEBERFAELLIG

/** Open todo whose due date is today (the dashboard "Heute fällig" rule). */
internal fun isDueToday(t: TodoDto): Boolean =
    t.status != "DONE" && Format.dueGroup(t.dueDate) == Format.DueGroup.HEUTE

/** Open todo whose due date is tomorrow (the dashboard "Morgen fällig" rule). */
internal fun isDueTomorrow(t: TodoDto): Boolean =
    t.status != "DONE" && Format.parseLocalDate(t.dueDate) == LocalDate.now().plusDays(1)

/** Done todo completed today, in the device timezone (the dashboard "Heute erledigt" rule). */
internal fun isDoneToday(t: TodoDto): Boolean =
    t.status == "DONE" && doneLocalDate(t.doneAt) == LocalDate.now()

/**
 * Done todo completed within the shared "last N days" window (#263) — used by the "Erledigt"
 * tab and the cross-list/list done-section. [windowDays] is the household-configured length
 * (#356), defaulting to [DONE_WINDOW_DAYS] for callers without a config read (HeuteScreen, tests).
 * A done todo without doneAt (rare, pre-migration) is excluded, like the today-only filter.
 */
internal fun isDoneInWindow(t: TodoDto, windowDays: Int = DONE_WINDOW_DAYS): Boolean {
    if (t.status != "DONE") return false
    val done = doneLocalDate(t.doneAt) ?: return false
    return !done.isBefore(LocalDate.now().minusDays((windowDays - 1).toLong()))
}

/**
 * What the "Erledigt" tab and the cross-list/list done-section actually show: the
 * windowed set ([isDoneInWindow], length [windowDays]) by default, or — when "Alle anzeigen" is
 * on (#340, [showAll]) — every DONE todo regardless of age (incl. ones without doneAt, which sort
 * last by the empty-string key). The COUNTS stay on "today" and are untouched.
 */
internal fun isDoneShown(t: TodoDto, showAll: Boolean, windowDays: Int = DONE_WINDOW_DAYS): Boolean =
    if (showAll) t.status == "DONE" else isDoneInWindow(t, windowDays)

/** Local calendar date of a done timestamp, in the device timezone (not UTC). */
internal fun doneLocalDate(doneAt: String?): LocalDate? =
    Format.parseInstant(doneAt)?.atZone(ZoneId.systemDefault())?.toLocalDate()

class TodoViewModel(
    private val repository: TodoRepository,
    private val configRepository: ConfigRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoUiState(isLoading = true))
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    init {
        load()
        loadDoneWindow()
        observeWebSocket()
    }

    /**
     * Fetch the household-configured "Erledigt"-window length (#356) into the UI state. Best-effort:
     * any failure leaves the default [DONE_WINDOW_DAYS] in place, so the view behaves exactly as
     * before this setting existed. Re-read on resume via [ensureConnected] so a change made on the
     * web/another device is picked up without a logout.
     */
    private fun loadDoneWindow() {
        viewModelScope.launch {
            configRepository.getDoneWindow().onSuccess { cfg ->
                _uiState.update { it.copy(doneWindowDays = cfg.days) }
            }
        }
    }

    fun load() {
        viewModelScope.launch { reload(showSpinner = true) }
    }

    /**
     * Pull-to-refresh entry point (#269). Suspends until the refetch completes so the UI's refresh
     * indicator can spin for the duration; no full-screen spinner (the list stays visible) but it
     * does surface a fetch error like load(), since it's user-triggered.
     */
    suspend fun refresh() = reload(showSpinner = false)

    /**
     * Refetch lists + todos. [showSpinner] drives the full-screen loading flag — true for the cold
     * load(), false for pull-to-refresh (the existing content stays put). On a transient failure the
     * previous lists/todos are kept (getOrDefault) so a dropped network never blanks the screen.
     */
    private suspend fun reload(showSpinner: Boolean) {
        if (showSpinner) _uiState.update { it.copy(isLoading = true, error = null) }
        val lists = repository.getLists()
        val todos = repository.getTodos()
        val error = lists.exceptionOrNull()?.message ?: todos.exceptionOrNull()?.message
        _uiState.update { state ->
            state.copy(
                lists = lists.getOrDefault(state.lists),
                todos = todos.getOrDefault(state.todos),
                isLoading = false,
                error = error,
            )
        }
    }

    /**
     * Silent background re-sync of lists + todos (#269). Fires on every WS (re)connect
     * (`onConnected`) and on app/screen resume ([ensureConnected]). A todo created/edited/deleted on
     * the web or another device while our socket was dead (Doze / mobile-network change / backend
     * restart) sends a TODO_* frame we never receive — without this refetch the list would stay stale
     * until logout/login. Unlike [reload] this never flips `isLoading` and leaves `error` untouched on
     * a transient failure — the next trigger retries.
     */
    private fun syncFromServer() {
        viewModelScope.launch {
            val lists = repository.getLists()
            val todos = repository.getTodos()
            _uiState.update { state ->
                state.copy(
                    lists = lists.getOrDefault(state.lists),
                    todos = todos.getOrDefault(state.todos),
                )
            }
        }
    }

    fun selectList(id: String?) = _uiState.update { it.copy(activeListId = id) }

    /**
     * Flip "Alle anzeigen" für die Erledigt-Historie (#340): lifts/restores the 14-day
     * cap on the displayed DONE content. In-memory per-session view state (the counts
     * stay on "today"); mirrors the web toggle, which additionally persists per-device.
     */
    fun toggleDoneShowAll() = _uiState.update { it.copy(doneShowAll = !it.doneShowAll) }

    /**
     * Open the tab a dashboard stat tile deep-links to (#255/#256). Mirrors the web, where the
     * tile sets the todos view's initial `activeId` to the matching sentinel — here it just
     * selects the corresponding (virtual) tab on the shared ViewModel.
     */
    fun applyFocus(focus: TodosFocus) = selectList(focus.tabId)

    /**
     * Quick-add an undated todo to the active list. In the Inbox tab [TodoUiState.activeList]
     * is null, so the POST carries no listId at all — the backend then creates a plain INBOX
     * todo (same contract as the Dashboard quick-add and the web Inbox tab, #77).
     *
     * Fire-and-forget for the quick-add bars (Aufgaben + Heute): this wrapper owns the global error
     * so a failed quick-add surfaces via the screen toast (#288). The edit sheet calls the suspending
     * [createTodo] directly and renders the returned message in-sheet instead — so it must NOT also
     * set the global error (no double-notify, #277/#288).
     */
    fun addTodo(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            createTodo(title).onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Create a todo and return the result so a caller (the edit sheet, #277) can stay open on
     * failure and show the reason inline. On success the new todo is upserted. Deliberately does
     * **not** set the global `_uiState.error` — the edit sheet surfaces the returned message itself;
     * the fire-and-forget [addTodo] wrapper sets the global error for the quick-add bars so the
     * screen toast covers them without the sheet path double-notifying (#288).
     *
     * The optional planning fields ([description]/[assignees]/[dueDate]/[priority]) carry the
     * quick-add "Details" panel (#393, mirrors the web QuickAdd). Each is only sent when set; the
     * backend derives the status from them (any assignee OR dueDate ⇒ PLANNED, else INBOX), so a
     * plain title-only call still creates an INBOX todo.
     */
    suspend fun createTodo(
        title: String,
        description: String? = null,
        assignees: List<String> = emptyList(),
        dueDate: String? = null,
        priority: String? = null,
    ): Result<TodoDto> {
        val listId = _uiState.value.activeList?.id
        return repository.createTodo(
            CreateTodoRequest(
                title = title.trim(),
                description = description?.trim()?.ifBlank { null },
                assignees = assignees.ifEmpty { null },
                dueDate = dueDate?.ifBlank { null },
                priority = priority?.ifBlank { null },
                listId = listId,
            ),
        ).onSuccess { upsertTodo(it) }
    }

    /**
     * Quick-add with the optional "Details" planning fields set (#393, mirrors the web
     * `QuickAdd.submit`). Fire-and-forget like [addTodo] — it owns the global error so a failed
     * capture surfaces via the screen toast — but suspends and returns whether the create succeeded
     * so the quick-add UI can reset its fields/panel only on success (and keep the typed value on
     * failure). A plain title-only capture still lands in the Inbox (no fields ⇒ backend INBOX).
     */
    suspend fun addPlannedTodo(
        title: String,
        description: String? = null,
        assignees: List<String> = emptyList(),
        dueDate: String? = null,
        priority: String? = null,
    ): Boolean {
        if (title.isBlank()) return false
        return createTodo(title, description, assignees, dueDate, priority)
            .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            .isSuccess
    }

    /**
     * Update a todo. [targetListId] files a list-less inbox todo into the picked list while
     * planning (#77). It is only sent when the todo is still list-less at save time — if the
     * partner moved it into a list while the sheet was open, the stale pick must not overwrite
     * that move (mirrors the web plan modal, #69). Null = „Bleibt in der Inbox" (unchanged).
     *
     * Fire-and-forget wrapper used by row actions (toggle done): it owns the global error so a failed
     * toggle surfaces via the screen toast (#288). The edit sheet calls the suspending [saveTodo]
     * directly and renders the returned message in-sheet — so [saveTodo] must NOT also set the global
     * error (no double-notify, #277/#288).
     */
    fun updateTodo(id: String, request: UpdateTodoRequest, targetListId: String? = null) {
        viewModelScope.launch {
            saveTodo(id, request, targetListId).onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Update a todo and return the result so the edit sheet (#277) can keep itself open on
     * failure and show the reason inline — only dismissing on success. On success the todo is
     * upserted. Deliberately does **not** set the global `_uiState.error` — the edit sheet surfaces
     * the returned message itself; the fire-and-forget [updateTodo]/[toggleDone] wrapper sets the
     * global error for the row actions so the screen toast covers them without the sheet path
     * double-notifying (#288).
     */
    suspend fun saveTodo(id: String, request: UpdateTodoRequest, targetListId: String? = null): Result<TodoDto> {
        val fileInto = targetListId?.takeIf { _uiState.value.todos.firstOrNull { t -> t.id == id }?.listId == null }
        val effective = if (fileInto != null) request.copy(listId = fileInto) else request
        return repository.updateTodo(id, effective)
            .onSuccess { upsertTodo(it) }
    }

    /** Toggle a todo between DONE and open (PLANNED when it has a plan, else INBOX). */
    fun toggleDone(todo: TodoDto) {
        val newStatus = if (todo.status == "DONE") {
            if (todo.assignees.isNotEmpty() || todo.dueDate != null) "PLANNED" else "INBOX"
        } else "DONE"
        updateTodo(todo.id, UpdateTodoRequest(status = newStatus))
    }

    fun deleteTodo(id: String) {
        viewModelScope.launch {
            repository.deleteTodo(id)
                .onSuccess { _uiState.update { s -> s.copy(todos = s.todos.filter { it.id != id }) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun createList(name: String, visibility: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createList(name.trim(), visibility)
                .onSuccess { list ->
                    _uiState.update { s ->
                        val lists = if (s.lists.any { it.id == list.id }) s.lists else s.lists + list
                        s.copy(lists = lists, activeListId = list.id)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    // --- Subtasks (each call returns the updated parent todo) ---

    fun addSubtask(todoId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.addSubtask(todoId, title.trim())
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun toggleSubtask(todoId: String, subtask: SubtaskDto) {
        viewModelScope.launch {
            repository.updateSubtask(todoId, subtask.id, UpdateSubtaskRequest(done = !subtask.done))
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteSubtask(todoId: String, subtaskId: String) {
        viewModelScope.launch {
            repository.deleteSubtask(todoId, subtaskId)
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun upsertTodo(todo: TodoDto) {
        _uiState.update { s ->
            val todos = if (s.todos.any { it.id == todo.id }) {
                s.todos.map { if (it.id == todo.id) todo else it }
            } else {
                listOf(todo) + s.todos
            }
            s.copy(todos = todos)
        }
    }

    private fun upsertList(list: TodoListDto) {
        _uiState.update { s ->
            val lists = if (s.lists.any { it.id == list.id }) {
                s.lists.map { if (it.id == list.id) list else it }
            } else {
                s.lists + list
            }
            s.copy(lists = lists)
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        // Re-sync on every (re)connect — the "server reachable again" signal (#269, mirrors the time
        // channel + shopping queue flush). The first connect also fires this; that one re-sync
        // overlaps load()'s fetch (harmless — cheap GETs at cold start), and every later reconnect
        // then reliably re-syncs without bespoke state.
        repository.setWebSocketOnConnected { syncFromServer() }
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is TodoWebSocketClient.WsEvent.TodoCreated -> upsertTodo(event.todo)
                    is TodoWebSocketClient.WsEvent.TodoUpdated -> upsertTodo(event.todo)
                    is TodoWebSocketClient.WsEvent.TodoDeleted ->
                        _uiState.update { s -> s.copy(todos = s.todos.filter { it.id != event.todo.id }) }
                    is TodoWebSocketClient.WsEvent.ListCreated -> upsertList(event.list)
                    is TodoWebSocketClient.WsEvent.ListUpdated -> upsertList(event.list)
                    is TodoWebSocketClient.WsEvent.ListDeleted ->
                        // a deleted list takes its todos with it (backend cascade) — drop both
                        _uiState.update { s ->
                            s.copy(
                                lists = s.lists.filter { it.id != event.list.id },
                                todos = s.todos.filter { it.listId != event.list.id },
                            )
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
        // also pick up an out-of-band change to the configurable done-window (#356) made elsewhere.
        loadDoneWindow()
    }

    override fun onCleared() {
        super.onCleared()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }
}
