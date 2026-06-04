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

    suspend fun getTodos(): Result<List<TodoDto>> = runCatching { api.getTodos() }

    suspend fun createTodo(request: CreateTodoRequest): Result<TodoDto> =
        runCatching { api.createTodo(request) }

    suspend fun updateTodo(id: String, request: UpdateTodoRequest): Result<TodoDto> =
        runCatching { api.updateTodo(id, request) }

    suspend fun deleteTodo(id: String): Result<Unit> =
        runCatching { api.deleteTodo(id) }

    // --- Todo lists ---

    suspend fun getLists(): Result<List<TodoListDto>> = runCatching { api.getTodoLists() }

    suspend fun createList(name: String, visibility: String?): Result<TodoListDto> =
        runCatching { api.createTodoList(CreateTodoListRequest(name, visibility)) }

    suspend fun updateList(id: String, request: UpdateTodoListRequest): Result<TodoListDto> =
        runCatching { api.updateTodoList(id, request) }

    suspend fun deleteList(id: String): Result<Unit> = runCatching { api.deleteTodoList(id) }

    // --- Subtasks (all return the updated parent todo) ---

    suspend fun addSubtask(todoId: String, title: String): Result<TodoDto> =
        runCatching { api.createSubtask(todoId, CreateSubtaskRequest(title)) }

    suspend fun updateSubtask(todoId: String, subtaskId: String, request: UpdateSubtaskRequest): Result<TodoDto> =
        runCatching { api.updateSubtask(todoId, subtaskId, request) }

    suspend fun deleteSubtask(todoId: String, subtaskId: String): Result<TodoDto> =
        runCatching { api.deleteSubtask(todoId, subtaskId) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun disconnectWebSocket() = wsClient.disconnect()
}
