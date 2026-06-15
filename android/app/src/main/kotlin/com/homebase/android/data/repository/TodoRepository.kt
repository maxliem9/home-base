package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateSubtaskRequest
import com.homebase.android.data.model.CreateTodoListRequest
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateSubtaskRequest
import com.homebase.android.data.model.UpdateTodoListRequest
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.websocket.TodoWebSocketClient
import kotlinx.coroutines.flow.Flow

class TodoRepository(
    private val api: HomeBaseApi,
    private val wsClient: TodoWebSocketClient,
) {
    val incomingEvents: Flow<TodoWebSocketClient.WsEvent> = wsClient.events

    // --- Todos ---

    suspend fun getTodos(): Result<List<TodoDto>> = apiCatching { api.getTodos() }

    suspend fun createTodo(request: CreateTodoRequest): Result<TodoDto> =
        apiCatching { api.createTodo(request) }

    suspend fun updateTodo(id: String, request: UpdateTodoRequest): Result<TodoDto> =
        apiCatching { api.updateTodo(id, request) }

    suspend fun deleteTodo(id: String): Result<Unit> =
        apiCatching { api.deleteTodo(id) }

    // --- Todo lists ---

    suspend fun getLists(): Result<List<TodoListDto>> = apiCatching { api.getTodoLists() }

    suspend fun createList(name: String, visibility: String?): Result<TodoListDto> =
        apiCatching { api.createTodoList(CreateTodoListRequest(name, visibility)) }

    suspend fun updateList(id: String, request: UpdateTodoListRequest): Result<TodoListDto> =
        apiCatching { api.updateTodoList(id, request) }

    suspend fun deleteList(id: String): Result<Unit> = apiCatching { api.deleteTodoList(id) }

    // --- Subtasks (all return the updated parent todo) ---

    suspend fun addSubtask(todoId: String, title: String): Result<TodoDto> =
        apiCatching { api.createSubtask(todoId, CreateSubtaskRequest(title)) }

    suspend fun updateSubtask(todoId: String, subtaskId: String, request: UpdateSubtaskRequest): Result<TodoDto> =
        apiCatching { api.updateSubtask(todoId, subtaskId, request) }

    suspend fun deleteSubtask(todoId: String, subtaskId: String): Result<TodoDto> =
        apiCatching { api.deleteSubtask(todoId, subtaskId) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /**
     * Register a "socket (re)connected, server reachable again" callback (#269). The ViewModel uses
     * it to silently refetch lists + todos after a drop, so a change made on another device while our
     * socket was dead (Doze / mobile-network change / backend restart) — whose WS event we missed —
     * shows up instead of leaving stale data on screen. Mirrors the time + shopping channels.
     */
    fun setWebSocketOnConnected(onConnected: (() -> Unit)?) {
        wsClient.onConnected = onConnected
    }
}
