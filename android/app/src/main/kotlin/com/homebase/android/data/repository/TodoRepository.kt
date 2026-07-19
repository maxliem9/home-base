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
import retrofit2.HttpException

class TodoRepository(
    private val api: HomeBaseApi,
    private val wsClient: TodoWebSocketClient,
) {
    val incomingEvents: Flow<TodoWebSocketClient.WsEvent> = wsClient.events

    // --- Todos ---

    // [doneSince] (YYYY-MM-DD, #591) windows the DONE todos server-side; null loads the full history.
    suspend fun getTodos(doneSince: String? = null): Result<List<TodoDto>> =
        apiCatching { api.getTodos(doneSince) }

    // Surface the backend's ErrorResponse.code as a typed [AppError] instead of a raw
    // "HTTP 400/404" so the edit sheet's in-sheet failure banner is understandable
    // (e.g. a validation 400, or a 404 if the partner deleted the todo mid-edit).
    suspend fun createTodo(request: CreateTodoRequest): Result<TodoDto> =
        apiCatching(mapHttpError = ::todoError) { api.createTodo(request) }

    suspend fun updateTodo(id: String, request: UpdateTodoRequest): Result<TodoDto> =
        apiCatching(mapHttpError = ::todoError) { api.updateTodo(id, request) }

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

    /**
     * Map a failed todo create/update response to a typed [AppError] via its ErrorResponse.code.
     * The UI resolves the code to text (strings.xml). Without this an HTTP 4xx/5xx would surface in
     * the edit sheet as the raw English "HTTP 400 Bad Request".
     */
    private fun todoError(e: HttpException): AppError = when (errorCodeOf(e)) {
        "INVALID_TODO" -> AppError.TODO_INVALID
        "INVALID_STATUS" -> AppError.TODO_INVALID_STATUS
        "INVALID_PRIORITY" -> AppError.TODO_INVALID_PRIORITY
        "INVALID_DUE_DATE" -> AppError.TODO_INVALID_DUE_DATE
        "INVALID_RECURRENCE" -> AppError.TODO_INVALID_RECURRENCE
        "INVALID_ID" -> AppError.TODO_INVALID_LIST
        "NOT_FOUND" -> AppError.TODO_NOT_FOUND
        else -> AppError.TODO_SAVE_FAILED
    }

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
