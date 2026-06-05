package com.homebase.android.data.websocket

import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class TodoWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<TodoWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        data class TodoCreated(val todo: TodoDto) : WsEvent()
        data class TodoUpdated(val todo: TodoDto) : WsEvent()
        data class TodoDeleted(val todo: TodoDto) : WsEvent()
        data class ListCreated(val list: TodoListDto) : WsEvent()
        data class ListUpdated(val list: TodoListDto) : WsEvent()
        data class ListDeleted(val list: TodoListDto) : WsEvent()
    }

    override val path = "/ws/todos"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val type = moshi.adapter(TypeEnvelope::class.java).fromJson(text)?.type ?: return null
        return when (type) {
            "TODO_CREATED" -> todo(text)?.let { WsEvent.TodoCreated(it) }
            "TODO_UPDATED" -> todo(text)?.let { WsEvent.TodoUpdated(it) }
            "TODO_DELETED" -> todo(text)?.let { WsEvent.TodoDeleted(it) }
            "TODO_LIST_CREATED" -> list(text)?.let { WsEvent.ListCreated(it) }
            "TODO_LIST_UPDATED" -> list(text)?.let { WsEvent.ListUpdated(it) }
            "TODO_LIST_DELETED" -> list(text)?.let { WsEvent.ListDeleted(it) }
            else -> null
        }
    }

    private fun todo(text: String): TodoDto? =
        moshi.adapter(TodoEnvelope::class.java).fromJson(text)?.payload

    private fun list(text: String): TodoListDto? =
        moshi.adapter(ListEnvelope::class.java).fromJson(text)?.payload

    @JsonClass(generateAdapter = true)
    internal data class TypeEnvelope(val type: String)

    @JsonClass(generateAdapter = true)
    internal data class TodoEnvelope(val type: String, val payload: TodoDto? = null)

    @JsonClass(generateAdapter = true)
    internal data class ListEnvelope(val type: String, val payload: TodoListDto? = null)
}
