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

@JsonClass(generateAdapter = true)
data class ShoppingItemDto(
    val id: String,
    val name: String,
    val category: String? = null,
    val checked: Boolean,
    val createdBy: String,
    val createdAt: String,
    val checkedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateShoppingItemRequest(
    val name: String,
    val category: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateShoppingItemRequest(
    val name: String? = null,
    val category: String? = null,
    val checked: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class ShoppingWsMessage(val type: String, val payload: ShoppingItemDto? = null)

@JsonClass(generateAdapter = true)
data class NoteDto(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val visibility: String,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

@JsonClass(generateAdapter = true)
data class CreateNoteRequest(
    val title: String,
    val content: String? = null,
    val tags: List<String>? = null,
    val visibility: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateNoteRequest(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val visibility: String? = null,
)

@JsonClass(generateAdapter = true)
data class NoteWsMessage(val type: String, val payload: NoteDto? = null)

@JsonClass(generateAdapter = true)
data class ProjectDto(
    val id: String,
    val name: String,
    val color: String,
    val archived: Boolean,
    val createdBy: String,
    val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class CreateProjectRequest(
    val name: String,
    val color: String,
)

@JsonClass(generateAdapter = true)
data class UpdateProjectRequest(
    val name: String? = null,
    val color: String? = null,
)

@JsonClass(generateAdapter = true)
data class ArchiveProjectRequest(
    val archived: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class TimeEntryDto(
    val id: String,
    val projectId: String,
    val userId: String,
    val startedAt: String,
    val stoppedAt: String? = null,
    val description: String? = null,
    val durationSeconds: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@JsonClass(generateAdapter = true)
data class StartTimerRequest(
    val projectId: String,
    val description: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateTimeEntryRequest(
    val projectId: String,
    val startedAt: String,
    val stoppedAt: String,
    val description: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTimeEntryRequest(
    val projectId: String? = null,
    val startedAt: String? = null,
    val stoppedAt: String? = null,
    val description: String? = null,
)

@JsonClass(generateAdapter = true)
data class TimeWsMessage(
    val type: String,
    val entry: TimeEntryDto? = null,
    val project: ProjectDto? = null,
)

@JsonClass(generateAdapter = true)
data class IngredientDto(
    val id: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val sortOrder: Int,
)

@JsonClass(generateAdapter = true)
data class RecipeStepDto(
    val id: String,
    val stepNumber: Int,
    val description: String,
)

@JsonClass(generateAdapter = true)
data class RecipeDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val servings: Int,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val category: String,
    val ingredients: List<IngredientDto> = emptyList(),
    val steps: List<RecipeStepDto> = emptyList(),
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

@JsonClass(generateAdapter = true)
data class IngredientInput(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
)

@JsonClass(generateAdapter = true)
data class RecipeStepInput(
    val description: String,
)

@JsonClass(generateAdapter = true)
data class CreateRecipeRequest(
    val title: String,
    val description: String? = null,
    val servings: Int? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val category: String,
    val ingredients: List<IngredientInput> = emptyList(),
    val steps: List<RecipeStepInput> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class UpdateRecipeRequest(
    val title: String? = null,
    val description: String? = null,
    val servings: Int? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val category: String? = null,
    val ingredients: List<IngredientInput>? = null,
    val steps: List<RecipeStepInput>? = null,
)

@JsonClass(generateAdapter = true)
data class RecipeWsMessage(val type: String, val payload: RecipeDto? = null)

enum class TodoStatus { INBOX, PLANNED, DONE }
enum class Priority { LOW, MEDIUM, HIGH }
enum class NoteVisibility { PRIVATE, SHARED }
enum class RecipeCategory { BREAKFAST, LUNCH, DINNER, SNACK, DESSERT, DRINK }
