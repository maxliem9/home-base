package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.websocket.TodoWebSocketClient
import kotlinx.coroutines.flow.Flow

class TodoRepository(
    private val api: HomeBaseApi,
    private val wsClient: TodoWebSocketClient,
) {
    val incomingEvents: Flow<TodoWebSocketClient.WsEvent> = wsClient.events

    suspend fun getTodos(): Result<List<TodoDto>> = runCatching { api.getTodos() }

    suspend fun createTodo(title: String): Result<TodoDto> =
        runCatching { api.createTodo(CreateTodoRequest(title)) }

    suspend fun updateTodo(id: String, request: UpdateTodoRequest): Result<TodoDto> =
        runCatching { api.updateTodo(id, request) }

    suspend fun deleteTodo(id: String): Result<Unit> =
        runCatching { api.deleteTodo(id) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun disconnectWebSocket() = wsClient.disconnect()
}
