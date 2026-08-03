package com.homebase.android.ui.aufgaben

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.RecurrenceDto
import com.homebase.android.data.model.SubtaskDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateSubtaskRequest
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.aufgaben.TodoSnapshot
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.repository.AppError
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    return !done.isBefore(doneWindowStart(windowDays))
}

/**
 * Inclusive lower bound of the done window: today minus (N-1) days, so a window of N days spans
 * today and the previous N-1 calendar days (local date). Shared by the local [isDoneInWindow] filter
 * and the server-side `?doneSince=` fetch bound (#591) so both use the identical cutoff.
 */
internal fun doneWindowStart(windowDays: Int = DONE_WINDOW_DAYS): LocalDate =
    LocalDate.now().minusDays((windowDays - 1).toLong())

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

// ---------------------------------------------------------------------------
// Edit-sheet auto-save — orchestrated in the ViewModel (survives the sheet closing), mirroring the
// notes editor. Editing an EXISTING todo pushes a normalized [TodoDraft] on every field change; the
// ViewModel debounces + serializes the saves and reports progress via [TodoEditorState]. New todos are
// created explicitly with the create button (see [createTodoFromDraft]) — never auto-created, so a
// stray "type a title and close" never spawns a todo.
// ---------------------------------------------------------------------------

/** Debounce window for the edit sheet's live auto-save — matches the notes editor (~1s after the last change). */
private const val TODO_AUTOSAVE_DEBOUNCE_MS = 1000L

/** Live auto-save status shown in the edit sheet footer (parity with the notes editor). */
enum class TodoSaveStatus { IDLE, SAVING, SAVED, ERROR }

/**
 * The full editable state of a todo, normalized to the shapes the backend expects. Value semantics
 * (data class) drive the dirty check: the sheet only saves when the draft differs from what was last
 * persisted. [targetListId] is the list currently selected in the sheet (null = Inbox / no list),
 * seeded to the todo's own list on open; the editor moves the todo to it on save but sends `listId`
 * only when it differs from the list at open time (#509, mirrors web's `listIdOriginal`, conflict-safe).
 */
data class TodoDraft(
    val title: String,
    val description: String,
    val assignees: List<String>,
    /** ISO date ("yyyy-MM-dd") or null. */
    val dueDate: String?,
    /** "HH:mm" or null; only meaningful with a date. */
    val dueTime: String?,
    val priority: String?,
    /** null = no recurrence rule. */
    val recurrence: RecurrenceDto?,
    val targetListId: String?,
)

/**
 * Progress of the open edit sheet's live auto-save (EXISTING todos only — a new todo is created
 * explicitly, never auto-saved). [id] is the todo being edited; [session] bumps once per open so the
 * sheet can key state on it.
 */
data class TodoEditorState(
    val id: String,
    val status: TodoSaveStatus = TodoSaveStatus.IDLE,
    val error: String? = null,
    val session: Int = 0,
)

/** Build the create request from a draft (null/omit semantics: blank/empty ⇒ omitted, backend derives status). */
internal fun TodoDraft.toCreateRequest(listId: String?): CreateTodoRequest = CreateTodoRequest(
    title = title.trim(),
    description = description.ifBlank { null },
    assignees = assignees.ifEmpty { null },
    dueDate = dueDate,
    // a time is meaningless without a date
    dueTime = if (dueDate != null) dueTime else null,
    priority = priority,
    recurrence = recurrence,
    listId = listId,
)

/**
 * Build the update request from a draft (#265/#429 convention: "" clears a field, a time without a
 * date is dropped, status is derived from assignee/date, and a null recurrence clears the rule with
 * freq "NONE"). Pure + top-level so it's unit-testable and matches the create mapping field-for-field.
 * [listId] follows the same #265 sentinel: null = list unchanged, "" = move to Inbox, a UUID = move to
 * that list — the editor resolves it against the open-time baseline (#509), so it's null by default.
 */
internal fun TodoDraft.toUpdateRequest(listId: String? = null): UpdateTodoRequest = UpdateTodoRequest(
    title = title.trim(),
    description = description.ifBlank { "" },
    // list analog of the #265 convention: [] clears all assignees, a non-empty list replaces the set.
    assignees = assignees,
    dueDate = dueDate ?: "",
    dueTime = if (dueDate != null) (dueTime ?: "") else "",
    priority = priority ?: "",
    status = if (assignees.isNotEmpty() || dueDate != null) "PLANNED" else "INBOX",
    listId = listId,
    recurrence = recurrence ?: RecurrenceDto("NONE"),
)

class TodoViewModel(
    private val repository: TodoRepository,
    private val configRepository: ConfigRepository,
    private val token: String,
    /**
     * Durable "last-known lists + todos" cache (#520, read-side twin of the shopping cache #517).
     * Seeded into state on a cold start so a launch with no connection shows the previous screen
     * instead of nothing, and re-mirrored on every change (server fetches AND optimistic edits).
     * null in tests that don't exercise it → no read-cache (behaves exactly as before).
     */
    private val snapshotStore: SnapshotStore<TodoSnapshot>? = null,
    // Resolves a repository AppError (carried by ApiException) to localized text via strings.xml (#558).
    // Default keeps the raw exception message (for tests); MainActivity injects the Context-backed one.
    private val errorText: (Throwable) -> String? = { it.message },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoUiState(isLoading = true))
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    /**
     * True once a fetch (reload / background sync) has successfully applied server data (#520). The
     * durable-cache restore checks this so it never stale-clobbers live server data that already
     * landed, and [reload] uses it to decide whether a failed refresh should surface a blocking error
     * (only when there is nothing to show anyway). Single-threaded (viewModelScope = Main).
     */
    private var hasServerData = false

    // --- Edit-sheet auto-save ---
    private val _todoEditor = MutableStateFlow<TodoEditorState?>(null)
    val todoEditor: StateFlow<TodoEditorState?> = _todoEditor.asStateFlow()

    /** Debounce timer: cancelled + re-armed on each field change, fires the save after the quiet window. */
    private var autosaveJob: Job? = null

    /**
     * The single in-flight save loop, or null/!isActive when idle. Saves are **serialized** through it:
     * while it runs no second save starts (so a live create can't double-POST); when it finishes it
     * re-checks the draft and saves again, so a keystroke that landed mid-save is still persisted.
     */
    private var saveJob: Job? = null

    /** Latest pushed draft + whether it may be saved (blank title / recurrence-without-date ⇒ false). */
    private var pendingDraft: TodoDraft? = null
    private var pendingValid: Boolean = false

    /** What is currently persisted on the server (dirty check). Null for a not-yet-created todo. */
    private var savedSnapshot: TodoDraft? = null

    /**
     * The edited todo's list at open time — the conflict baseline for list moves (#509, web's
     * `listIdOriginal`). `listId` is PUT only when the picked list differs from this; rebased to the
     * new list after each own move persists, so a partner's concurrent move isn't clobbered by a later
     * unrelated auto-save (mirrors the assignee/dueDate rebasing on web).
     */
    private var editorListIdOriginal: String? = null

    /** Monotonic editor-session counter; bumped on each open. */
    private var editorSession = 0

    init {
        load()
        loadDoneWindow()
        observeWebSocket()
        restoreAndMirrorSnapshot()
    }

    /**
     * Offline read-cache (#520). First seed the last-known lists + todos from disk so a cold start
     * with no connection shows the previous screen instead of nothing; then mirror every subsequent
     * change back so the cache always reflects what the user last saw (optimistic edits included).
     *
     * Ordering matters: the seed runs the disk read *before* the mirror collector starts, so the
     * collector can never persist the pre-restore empty frame over a good cache. The seed only fills
     * fields that are still empty and bails if a fetch already won ([hasServerData]) — a slow disk
     * read must not clobber fresh server data. Restoring content clears any refresh error: offline we
     * deliberately "show the old state" silently; the reconnect/backstop resync restores correctness.
     */
    private fun restoreAndMirrorSnapshot() {
        val store = snapshotStore ?: return
        viewModelScope.launch {
            val cached = store.load()
            if (cached != null && !hasServerData && (cached.lists.isNotEmpty() || cached.todos.isNotEmpty())) {
                _uiState.update { s ->
                    if (hasServerData) s
                    else s.copy(
                        lists = s.lists.ifEmpty { cached.lists },
                        todos = s.todos.ifEmpty { cached.todos },
                        isLoading = false,
                        error = null,
                    )
                }
            }
            // Persist on every distinct lists/todos change from here on. Starting the collector only
            // after the seed read guarantees we never overwrite the cache with the initial empty state.
            uiState
                .map { it.lists to it.todos }
                .distinctUntilChanged()
                .collect { (lists, todos) -> store.save(TodoSnapshot(lists = lists, todos = todos)) }
        }
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
                val changed = _uiState.value.doneWindowDays != cfg.days
                _uiState.update { it.copy(doneWindowDays = cfg.days) }
                // The cold load() fetched with the default window (#591 `?doneSince=`). A configured
                // window *larger* than the default would leave older DONE todos unfetched — refetch once
                // with the real window so the Erledigt tab isn't silently capped. Skipped in "Alle
                // anzeigen" mode (doneSince is null there — the window is irrelevant).
                if (changed && !_uiState.value.doneShowAll) syncFromServer()
            }
        }
    }

    /**
     * The `?doneSince=` window bound for the /todos fetch (#591): today minus (N-1) days as a local
     * ISO date, matching [isDoneInWindow]/[doneWindowStart]. Null in "Alle anzeigen" mode so the server
     * returns the full DONE history. Open todos always come back regardless. Reads live state so a
     * refetch after a config/toggle change uses the current window.
     */
    private fun doneSinceParam(): String? {
        val s = _uiState.value
        return if (s.doneShowAll) null else doneWindowStart(s.doneWindowDays).toString()
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
        val todos = repository.getTodos(doneSinceParam())
        val error = lists.exceptionOrNull()?.let(errorText) ?: todos.exceptionOrNull()?.let(errorText)
        if (error == null) hasServerData = true // a successful fetch landed → the cache seed must not clobber it (#520)
        _uiState.update { state ->
            val nextLists = lists.getOrDefault(state.lists)
            val nextTodos = todos.getOrDefault(state.todos)
            state.copy(
                lists = nextLists,
                todos = nextTodos,
                isLoading = false,
                // Keep `error` set only when there is nothing to show anyway (#520). With cached or prior
                // data already on screen, a failed background refresh leaves the old state in place with
                // no blocking error — the reconnect/backstop resync restores correctness.
                error = error?.takeIf { nextLists.isEmpty() && nextTodos.isEmpty() },
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
            val todos = repository.getTodos(doneSinceParam())
            // A successful re-sync also counts as server data landing → the cache seed must not
            // clobber it (#520). Guard on both fetches succeeding (a partial failure keeps prior state).
            if (lists.isSuccess && todos.isSuccess) hasServerData = true
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
    fun toggleDoneShowAll() {
        _uiState.update { it.copy(doneShowAll = !it.doneShowAll) }
        // The DONE set on the wire is server-windowed (#591): entering "Alle anzeigen" needs a
        // param-less refetch to pull the full history; leaving it re-applies the window. Silent
        // (no spinner), best-effort — a transient failure keeps the current todos and retries on
        // the next resync. WS meanwhile keeps pushing single DONE todos idempotently.
        syncFromServer()
    }

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
            createTodo(title).onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
            .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
            .isSuccess
    }

    /**
     * Fire-and-forget update used by row actions (toggle done): it owns the global error so a failed
     * toggle surfaces via the screen toast (#288). The edit sheet calls the suspending [saveTodo]
     * directly and renders the returned message in-sheet — so [saveTodo] must NOT also set the global
     * error (no double-notify, #277/#288).
     */
    fun updateTodo(id: String, request: UpdateTodoRequest) {
        viewModelScope.launch {
            saveTodo(id, request).onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /**
     * Update a todo and return the result so the edit sheet (#277) can keep itself open on
     * failure and show the reason inline — only dismissing on success. On success the todo is
     * upserted. Deliberately does **not** set the global `_uiState.error` — the edit sheet surfaces
     * the returned message itself; the fire-and-forget [updateTodo]/[toggleDone] wrapper sets the
     * global error for the row actions so the screen toast covers them without the sheet path
     * double-notifying (#288). List moves ride on [request]`.listId`, resolved by the editor (#509).
     */
    suspend fun saveTodo(id: String, request: UpdateTodoRequest): Result<TodoDto> =
        repository.updateTodo(id, request).onSuccess { upsertTodo(it) }

    // --- Edit-sheet auto-save (existing todos): open → live draft pushes → flush/close ---

    /**
     * Explicitly create a todo from the edit sheet's draft. New todos are **not** auto-saved (the user
     * commits with the create button), so a stray "type a title and close" never spawns one. Full-field
     * create incl. due time + recurrence; upserts on success. Deliberately does not set the global error
     * — the create sheet surfaces the returned message inline (like [createTodo], #277/#288).
     */
    suspend fun createTodoFromDraft(draft: TodoDraft): Result<TodoDto> =
        repository.createTodo(draft.toCreateRequest(_uiState.value.activeList?.id)).onSuccess { upsertTodo(it) }

    /**
     * Begin a live auto-save session for an existing [todo]. Seeds the dirty baseline to the loaded
     * state, so opening alone never saves; the first keystroke is the first dirty change.
     */
    fun openTodoEditor(todo: TodoDto) {
        autosaveJob?.cancel()
        saveJob?.cancel()
        pendingDraft = null
        pendingValid = false
        editorSession += 1
        savedSnapshot = draftOf(todo)
        editorListIdOriginal = todo.listId
        _todoEditor.value = TodoEditorState(id = todo.id, session = editorSession)
    }

    /**
     * Apply an edit-sheet field change and (re)arm the debounced auto-save. Each call cancels the
     * pending timer and starts a fresh [TODO_AUTOSAVE_DEBOUNCE_MS] window, so we persist ~1s after the
     * last change rather than on every keystroke. A no-op change (draft already saved) or an invalid
     * draft (blank title / recurrence without a due date) clears the timer and saves nothing — the last
     * valid save stays on the server.
     */
    fun updateTodoDraft(draft: TodoDraft, valid: Boolean) {
        if (_todoEditor.value == null) return
        pendingDraft = draft
        pendingValid = valid
        autosaveJob?.cancel()
        if (!valid || !isDirty(draft)) return
        // clear a stale "Gespeichert" as soon as a new edit lands
        if (_todoEditor.value?.status == TodoSaveStatus.SAVED) setEditorStatus(TodoSaveStatus.IDLE)
        autosaveJob = viewModelScope.launch {
            delay(TODO_AUTOSAVE_DEBOUNCE_MS)
            requestTodoSave()
        }
    }

    /**
     * Flush the pending draft immediately (no debounce), then clear the editor — called when the sheet
     * closes via ✕/scrim/back, so the last change is never lost between the final keystroke and the
     * debounce. Runs in the ViewModel scope so it outlives the sheet's composition; a close-save that
     * fails surfaces via the global toast (the in-sheet marker is already gone).
     */
    fun closeTodoEditor() {
        autosaveJob?.cancel()
        viewModelScope.launch {
            requestTodoSave()
            saveJob?.join()
            if (_todoEditor.value?.status == TodoSaveStatus.ERROR) {
                _uiState.update { it.copy(error = _todoEditor.value?.error) }
            }
            _todoEditor.value = null
            pendingDraft = null
            savedSnapshot = null
        }
    }

    /**
     * Discard the edit session without saving (the trash action): deletes an already-created todo
     * ([id] non-null) or just drops a not-yet-created draft. Cancels any in-flight/pending save.
     */
    fun discardTodoEditor(id: String?) {
        autosaveJob?.cancel()
        saveJob?.cancel()
        pendingDraft = null
        savedSnapshot = null
        _todoEditor.value = null
        if (id != null) deleteTodo(id)
    }

    /** Whether [draft] differs from what's persisted (no snapshot yet ⇒ a brand-new todo is always dirty). */
    private fun isDirty(draft: TodoDraft): Boolean = savedSnapshot?.let { it != draft } ?: true

    private fun setEditorStatus(status: TodoSaveStatus, error: String? = null) =
        _todoEditor.update { it?.copy(status = status, error = error) }

    /**
     * Start the serialized save loop if it isn't already running. A running loop re-checks the draft
     * when it finishes a request, so we never start a second concurrent save (no duplicate create) yet
     * never drop a mid-save edit either.
     */
    private fun requestTodoSave() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            while (true) {
                val draft = pendingDraft ?: break
                if (!pendingValid || !isDirty(draft)) break
                val ok = saveTodoOnce(draft)
                if (!ok) break
            }
        }
    }

    /** Persist [draft] once (update — new todos are created explicitly, not through this loop). */
    private suspend fun saveTodoOnce(draft: TodoDraft): Boolean {
        val editor = _todoEditor.value ?: return false
        setEditorStatus(TodoSaveStatus.SAVING)
        // #509 sentinel: send listId only on a real move vs the open-time baseline (null = unchanged,
        // "" = move to Inbox, UUID = move to that list) — an untouched picker never clobbers a
        // concurrent partner move.
        val listIdArg = when (val picked = draft.targetListId) {
            editorListIdOriginal -> null
            null -> ""
            else -> picked
        }
        return saveTodo(editor.id, draft.toUpdateRequest(listIdArg)).fold(
            onSuccess = {
                savedSnapshot = draft
                // rebase the baseline so a later unrelated auto-save doesn't re-send this move
                editorListIdOriginal = draft.targetListId
                setEditorStatus(TodoSaveStatus.SAVED)
                true
            },
            onFailure = { e ->
                // A suppressed session-expiry 401 (errorText → null, #614) leaves the editor as-is; the
                // central logout tears the sheet down. A real error flips it to ERROR with its message.
                errorText(e)?.let { setEditorStatus(TodoSaveStatus.ERROR, it) }
                false
            },
        )
    }

    /** The todo's loaded server state as a draft — the dirty baseline for an existing todo. */
    private fun draftOf(todo: TodoDto): TodoDraft = TodoDraft(
        title = todo.title,
        description = todo.description ?: "",
        assignees = todo.assignees,
        dueDate = todo.dueDate,
        // Normalize exactly like the sheet's draft (parse → "HH:mm") so a legacy "HH:mm:ss" value can't
        // make the baseline differ from an untouched draft and fire a phantom auto-save on open.
        dueTime = if (todo.dueDate != null) Format.parseLocalTime(todo.dueTime)?.let { Format.hhmm(it) } else null,
        priority = todo.priority,
        recurrence = todo.recurrence,
        // seed to the todo's own list so opening never counts as a move (#509)
        targetListId = todo.listId,
    )

    /** Toggle a todo between DONE and open (PLANNED when it has a plan, else INBOX). */
    fun toggleDone(todo: TodoDto) {
        val newStatus = if (todo.status == "DONE") {
            if (todo.assignees.isNotEmpty() || todo.dueDate != null) "PLANNED" else "INBOX"
        } else "DONE"
        updateTodo(todo.id, UpdateTodoRequest(status = newStatus))
    }

    /**
     * Quick-Edit aus der Zeile: **nur** Fälligkeit (Datum + optionale Uhrzeit). Der Status wird
     * genauso neu berechnet wie im Plan-Sheet — nimmt man einer Aufgabe ohne Zuständige das Datum,
     * fällt sie zurück in die Inbox. Der jeweils andere Anker und der DONE-Zustand werden **live**
     * aus dem State gelesen (nicht aus einem Snapshot der Zeile), damit eine erledigte Aufgabe
     * erledigt bleibt und eine parallel eingetroffene Zuständigen-Änderung nicht überschrieben
     * wird. Spiegelt web `handleDateEdit`.
     */
    suspend fun quickEditDue(id: String, dueDate: String?, dueTime: String?): String? {
        // unter uns verschwunden (WS-Delete) → nichts zu tun, Sheet einfach schließen
        val cur = _uiState.value.todos.firstOrNull { it.id == id } ?: return null
        // #628: eine wiederkehrende Aufgabe braucht ihr Datum als Anker — das Backend lehnt das
        // Löschen mit INVALID_RECURRENCE ab. Das Sheet bietet es gar nicht erst an (✕ ausgeblendet);
        // dieser Guard liest die Wiederholung LIVE und greift, wenn sie erst hinzukam, während das
        // Sheet offen war (WS-Race auf dem Zeilen-Snapshot). Meldung = dieselbe wie vom Backend.
        if (dueDate == null && cur.recurrence != null) {
            return errorText(ApiException(AppError.TODO_INVALID_RECURRENCE, IllegalStateException(id)))
        }
        return quickSave(
            id,
            UpdateTodoRequest(
                status = when {
                    cur.status == "DONE" -> null
                    dueDate != null || cur.assignees.isNotEmpty() -> "PLANNED"
                    else -> "INBOX"
                },
                // "" löscht das Datum — nur senden, wenn vorher überhaupt eines gesetzt war (#468)
                dueDate = dueDate ?: if (cur.dueDate != null) "" else null,
                // eine Uhrzeit ohne Datum ist bedeutungslos → mit dem Datum zwangs-löschen
                dueTime = if (dueDate != null) (dueTime ?: "") else "",
            ),
        )
    }

    /** Quick-Edit aus der Zeile: **nur** die Zuständigen; Live-Re-Read wie [quickEditDue]. */
    suspend fun quickEditAssignees(id: String, assignees: List<String>): String? {
        val cur = _uiState.value.todos.firstOrNull { it.id == id } ?: return null
        return quickSave(
            id,
            UpdateTodoRequest(
                status = when {
                    cur.status == "DONE" -> null
                    assignees.isNotEmpty() || cur.dueDate != null -> "PLANNED"
                    else -> "INBOX"
                },
                // [] leert die Menge (Listen-Analogon zu #265)
                assignees = assignees,
            ),
        )
    }

    /**
     * Speichert einen Quick-Edit und gibt `null` bei Erfolg bzw. die Fehlermeldung zurück, damit das
     * aufrufende Sheet offen bleiben und den Grund inline zeigen kann (statt die Eingabe zu verlieren
     * — genau das macht web mit `if (ok) setDateEdit(null)`). Setzt bewusst NICHT den globalen
     * `error`: das Sheet zeigt die Meldung selbst, sonst doppelt es mit dem Screen-Toast (#277/#288).
     *
     * Ein echter Fall dafür: bei einer wiederkehrenden Aufgabe das Datum löschen — das Backend
     * lehnt mit `INVALID_RECURRENCE` ab („a recurring todo needs a dueDate as its schedule anchor"),
     * weil die Wiederholung ihren Anker verlöre.
     */
    private suspend fun quickSave(id: String, request: UpdateTodoRequest): String? =
        saveTodo(id, request).exceptionOrNull()?.let { errorText(it) }

    fun deleteTodo(id: String) {
        viewModelScope.launch {
            repository.deleteTodo(id)
                .onSuccess { _uiState.update { s -> s.copy(todos = s.todos.filter { it.id != id }) } }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    // --- Subtasks (each call returns the updated parent todo) ---

    fun addSubtask(todoId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.addSubtask(todoId, title.trim())
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    fun toggleSubtask(todoId: String, subtask: SubtaskDto) {
        viewModelScope.launch {
            repository.updateSubtask(todoId, subtask.id, UpdateSubtaskRequest(done = !subtask.done))
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    fun deleteSubtask(todoId: String, subtaskId: String) {
        viewModelScope.launch {
            repository.deleteSubtask(todoId, subtaskId)
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
