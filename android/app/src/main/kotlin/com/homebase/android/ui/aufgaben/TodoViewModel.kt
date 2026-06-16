package com.homebase.android.ui.aufgaben

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.SubtaskDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateSubtaskRequest
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sentinel tab id for the built-in Inbox tab (issue #77). Real list ids are
 * UUIDs, so this can never collide — mirrors the web's `INBOX_ID`.
 */
const val INBOX_TAB_ID = "__inbox__"

data class TodoUiState(
    val lists: List<TodoListDto> = emptyList(),
    val todos: List<TodoDto> = emptyList(),
    val activeListId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /**
     * Whether the Inbox tab is active — either explicitly selected, or as the
     * default tab when no lists exist yet (#77, same rule as the web TodosView).
     */
    val inboxActive: Boolean get() = activeListId == INBOX_TAB_ID || lists.isEmpty()

    /** The selected list, falling back to the first one; null while the Inbox tab is active. */
    val activeList: TodoListDto?
        get() = if (inboxActive) null else lists.firstOrNull { it.id == activeListId } ?: lists.firstOrNull()

    /**
     * Todos shown for the active tab. Inbox = alles Unverplante (#71/#77): status-INBOX
     * todos — auch wenn sie schon in einer Liste liegen — plus alle listen-losen Todos
     * unabhängig vom Status, damit nichts unerreichbar wird. `listId` kann im JSON ganz
     * fehlen (encodeDefaults=false, #46) und ist dann hier null. Listen-Tabs zeigen exakt
     * ihre eigenen Todos — das frühere Catch-all-Verhalten des ersten Tabs entfällt.
     */
    val visibleTodos: List<TodoDto>
        get() {
            if (inboxActive) return todos.filter { it.status == "INBOX" || it.listId == null }
            val id = activeList?.id ?: return emptyList()
            return todos.filter { it.listId == id }
        }

    /** Inbox tab badge: number of status-INBOX todos — same rule as the HeuteScreen tile (#71). */
    val inboxCount: Int get() = todos.count { it.status == "INBOX" }

    /** Count of open (not done) todos across all lists — used for the drawer badge. */
    val openCount: Int get() = todos.count { it.status != "DONE" }
}

class TodoViewModel(
    private val repository: TodoRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoUiState(isLoading = true))
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
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
     * Quick-add an undated todo to the active list. In the Inbox tab [TodoUiState.activeList]
     * is null, so the POST carries no listId at all — the backend then creates a plain INBOX
     * todo (same contract as the Dashboard quick-add and the web Inbox tab, #77).
     */
    fun addTodo(title: String) {
        if (title.isBlank()) return
        val listId = _uiState.value.activeList?.id
        viewModelScope.launch {
            repository.createTodo(CreateTodoRequest(title = title.trim(), listId = listId))
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Update a todo. [targetListId] files a list-less inbox todo into the picked list while
     * planning (#77). It is only sent when the todo is still list-less at save time — if the
     * partner moved it into a list while the sheet was open, the stale pick must not overwrite
     * that move (mirrors the web plan modal, #69). Null = „Bleibt in der Inbox" (unchanged).
     */
    fun updateTodo(id: String, request: UpdateTodoRequest, targetListId: String? = null) {
        val fileInto = targetListId?.takeIf { _uiState.value.todos.firstOrNull { t -> t.id == id }?.listId == null }
        val effective = if (fileInto != null) request.copy(listId = fileInto) else request
        viewModelScope.launch {
            repository.updateTodo(id, effective)
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Toggle a todo between DONE and open (PLANNED when it has a plan, else INBOX). */
    fun toggleDone(todo: TodoDto) {
        val newStatus = if (todo.status == "DONE") {
            if (todo.assignee != null || todo.dueDate != null) "PLANNED" else "INBOX"
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
    }

    override fun onCleared() {
        super.onCleared()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }
}
