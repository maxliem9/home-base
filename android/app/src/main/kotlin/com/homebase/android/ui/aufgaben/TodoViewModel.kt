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

data class TodoUiState(
    val lists: List<TodoListDto> = emptyList(),
    val todos: List<TodoDto> = emptyList(),
    val activeListId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /** The selected list, falling back to the first one. */
    val activeList: TodoListDto? get() = lists.firstOrNull { it.id == activeListId } ?: lists.firstOrNull()

    /** Whether [activeList] is the first (catch-all) tab — it also surfaces unfiled inbox todos. */
    val activeIsFirst: Boolean get() = activeList != null && lists.firstOrNull()?.id == activeList?.id

    /**
     * Todos shown for the active list. The first list doubles as the catch-all and additionally
     * surfaces unfiled (listId == null) inbox todos so nothing is hidden on mobile.
     */
    val visibleTodos: List<TodoDto>
        get() {
            val id = activeList?.id ?: return todos.filter { it.listId == null }
            return todos.filter { it.listId == id || (activeIsFirst && it.listId == null) }
        }

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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
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
    }

    fun selectList(id: String?) = _uiState.update { it.copy(activeListId = id) }

    /** Quick-add an undated todo to the active list (or inbox if no list is selected). */
    fun addTodo(title: String) {
        if (title.isBlank()) return
        val listId = _uiState.value.activeList?.id
        viewModelScope.launch {
            repository.createTodo(CreateTodoRequest(title = title.trim(), listId = listId))
                .onSuccess { upsertTodo(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun updateTodo(id: String, request: UpdateTodoRequest) {
        viewModelScope.launch {
            repository.updateTodo(id, request)
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
                        _uiState.update { s -> s.copy(lists = s.lists.filter { it.id != event.list.id }) }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
