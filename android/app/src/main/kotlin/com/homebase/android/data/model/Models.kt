package com.homebase.android.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TodoDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val createdBy: String,
    val createdAt: String,
    val doneAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateTodoRequest(val title: String)

@JsonClass(generateAdapter = true)
data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
)

@JsonClass(generateAdapter = true)
data class LoginRequest(val username: String, val password: String)

@JsonClass(generateAdapter = true)
data class TokenResponse(val token: String)

@JsonClass(generateAdapter = true)
data class WsMessage(val type: String, val payload: TodoDto? = null)

enum class TodoStatus { INBOX, PLANNED, DONE }
enum class Priority { LOW, MEDIUM, HIGH }
