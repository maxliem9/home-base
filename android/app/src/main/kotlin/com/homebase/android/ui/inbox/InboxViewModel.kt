package com.homebase.android.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InboxUiState(
    val todos: List<TodoDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class InboxViewModel(
    private val repository: TodoRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InboxUiState(isLoading = true))
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getTodos()
                .onSuccess { todos ->
                    _uiState.update { it.copy(todos = todos, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun createTodo(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.createTodo(title.trim())
                .onSuccess { todo ->
                    _uiState.update { state ->
                        state.copy(todos = listOf(todo) + state.todos)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun markDone(id: String) {
        viewModelScope.launch {
            repository.updateTodo(id, UpdateTodoRequest(status = "DONE"))
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(todos = state.todos.map { if (it.id == id) updated else it })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun deleteTodo(id: String) {
        viewModelScope.launch {
            repository.deleteTodo(id)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(todos = state.todos.filter { it.id != id })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is TodoWebSocketClient.WsEvent.TodoCreated -> {
                        _uiState.update { state ->
                            if (state.todos.none { it.id == event.todo.id })
                                state.copy(todos = listOf(event.todo) + state.todos)
                            else state
                        }
                    }
                    is TodoWebSocketClient.WsEvent.TodoUpdated -> {
                        _uiState.update { state ->
                            state.copy(
                                todos = state.todos.map { if (it.id == event.todo.id) event.todo else it }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
