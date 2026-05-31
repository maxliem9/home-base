package com.homebase.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val code: String, val message: String)

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(val token: String)

@Serializable
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
    val doneAt: String? = null
)

@Serializable
data class CreateTodoRequest(
    val title: String,
    val description: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null
)

@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null
)

@Serializable
data class WsMessage(val type: String, val payload: TodoDto? = null)

@Serializable
data class ShoppingItemDto(
    val id: String,
    val name: String,
    val category: String? = null,
    val checked: Boolean,
    val createdBy: String,
    val createdAt: String,
    val checkedAt: String? = null
)

@Serializable
data class CreateShoppingItemRequest(
    val name: String,
    val category: String? = null
)

@Serializable
data class UpdateShoppingItemRequest(
    val name: String? = null,
    val category: String? = null,
    val checked: Boolean? = null
)

@Serializable
data class ShoppingWsMessage(val type: String, val payload: ShoppingItemDto? = null)

@Serializable
data class NoteDto(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val visibility: String,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CreateNoteRequest(
    val title: String,
    val content: String? = null,
    val tags: List<String>? = null,
    val visibility: String? = null
)

@Serializable
data class UpdateNoteRequest(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val visibility: String? = null
)

@Serializable
data class NoteWsMessage(val type: String, val payload: NoteDto? = null)
