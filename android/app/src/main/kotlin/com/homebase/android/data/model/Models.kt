package com.homebase.android.data.model

import com.squareup.moshi.JsonClass

// ---------------------------------------------------------------------------
// Auth & config
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class LoginRequest(val username: String, val password: String)

@JsonClass(generateAdapter = true)
data class TokenResponse(val token: String)

@JsonClass(generateAdapter = true)
data class AppConfigResponse(val householdName: String)

// ---------------------------------------------------------------------------
// Todos, todo lists & subtasks
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class SubtaskDto(
    val id: String,
    val title: String,
    val done: Boolean,
    val sortOrder: Int,
)

/** Recurrence rule on a todo (issue #44). freq DAILY|WEEKLY|MONTHLY; on an update "NONE" clears it. */
@JsonClass(generateAdapter = true)
data class RecurrenceDto(
    val freq: String,
    val interval: Int = 1,
)

@JsonClass(generateAdapter = true)
data class TodoDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val listId: String? = null,
    val recurrence: RecurrenceDto? = null,
    val subtasks: List<SubtaskDto> = emptyList(),
    val createdBy: String,
    val createdAt: String,
    val doneAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateTodoRequest(
    val title: String,
    val description: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val listId: String? = null,
    val recurrence: RecurrenceDto? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    // null = unchanged, "" = remove from list, UUID = move to that list
    val listId: String? = null,
    // null = unchanged; freq "NONE" clears it; otherwise sets/updates the rule
    val recurrence: RecurrenceDto? = null,
)

@JsonClass(generateAdapter = true)
data class CreateSubtaskRequest(val title: String)

@JsonClass(generateAdapter = true)
data class UpdateSubtaskRequest(
    val title: String? = null,
    val done: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class TodoListDto(
    val id: String,
    val name: String,
    val visibility: String,
    val createdBy: String,
    val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class CreateTodoListRequest(
    val name: String,
    val visibility: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTodoListRequest(
    val name: String? = null,
    val visibility: String? = null,
)

@JsonClass(generateAdapter = true)
data class WsMessage(val type: String, val payload: TodoDto? = null)

@JsonClass(generateAdapter = true)
data class TodoListWsMessage(val type: String, val payload: TodoListDto? = null)

// ---------------------------------------------------------------------------
// Shopping items & lists
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class ShoppingItemDto(
    val id: String,
    val name: String,
    val listId: String? = null,
    val checked: Boolean,
    val createdBy: String,
    val createdAt: String,
    val checkedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateShoppingItemRequest(
    val name: String,
    val listId: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateShoppingItemRequest(
    val name: String? = null,
    // null = unchanged, "" = remove from list, UUID = move to that list
    val listId: String? = null,
    val checked: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class ShoppingListDto(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class CreateShoppingListRequest(val name: String)

@JsonClass(generateAdapter = true)
data class UpdateShoppingListRequest(val name: String? = null)

@JsonClass(generateAdapter = true)
data class ShoppingWsMessage(val type: String, val payload: ShoppingItemDto? = null)

/** One recipe ingredient (already serving-scaled) for [BatchAddShoppingRequest]. */
@JsonClass(generateAdapter = true)
data class ShoppingLineInput(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
)

@JsonClass(generateAdapter = true)
data class BatchAddShoppingRequest(
    val listId: String? = null,
    val items: List<ShoppingLineInput> = emptyList(),
)

/** Result of a batch add: created items, quantities merged into existing ones, skipped dupes. */
@JsonClass(generateAdapter = true)
data class BatchAddShoppingResponse(
    val added: Int,
    val merged: Int,
    val skipped: Int,
    val items: List<ShoppingItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ShoppingListWsMessage(val type: String, val payload: ShoppingListDto? = null)

// ---------------------------------------------------------------------------
// Notes
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class NoteImageDto(
    val id: String,
    val noteId: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sortOrder: Int,
    val createdBy: String,
    val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class NoteDto(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val visibility: String,
    val images: List<NoteImageDto> = emptyList(),
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

// ---------------------------------------------------------------------------
// Time tracking
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Recipes
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class IngredientDto(
    val id: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    // optional group label, e.g. "Boden" / "Topping"; null = ungrouped (top section)
    val section: String? = null,
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
    val section: String? = null,
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

// ---------------------------------------------------------------------------
// Abwesenheit / Familienkalender
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class AbsenceDto(
    val id: String,
    val userId: String,
    val date: String,
    val type: String,
    val half: String? = null,
)

@JsonClass(generateAdapter = true)
data class PartTimeRuleDto(
    val id: String,
    val userId: String,
    val weekday: Int,
    val start: String,
    val end: String? = null,
)

@JsonClass(generateAdapter = true)
data class KitaClosureDto(
    val id: String,
    val date: String,
    val label: String,
)

@JsonClass(generateAdapter = true)
data class AbsSettingsDto(
    val userId: String,
    val state: String,
    val allowance: Double,
    val carryover: Double,
    val carryoverExpires: String? = null,
    val kindKrankCap: Int,
)

/** Full planner snapshot — clients refetch this after any change. */
@JsonClass(generateAdapter = true)
data class AbsenceStateDto(
    val users: List<String> = emptyList(),
    val absences: List<AbsenceDto> = emptyList(),
    val partTime: List<PartTimeRuleDto> = emptyList(),
    val kitaClosures: List<KitaClosureDto> = emptyList(),
    val settings: List<AbsSettingsDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SetAbsenceRequest(
    val userId: String,
    val date: String,
    val type: String,
    val half: String? = null,
)

/** Bulk apply (or clear, when type is null) on the given dates. */
@JsonClass(generateAdapter = true)
data class BatchAbsenceRequest(
    val userId: String,
    val type: String? = null,
    val half: String? = null,
    val dates: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreatePartTimeRequest(
    val userId: String,
    val weekday: Int,
    val start: String,
    val end: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdatePartTimeRequest(
    val weekday: Int,
    val start: String,
    val end: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateKitaRequest(
    val date: String,
    val label: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateKitaRangeRequest(
    val from: String,
    val to: String,
    val label: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateKitaRequest(
    val date: String? = null,
    val label: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateAbsSettingsRequest(
    val state: String? = null,
    val allowance: Double? = null,
    val carryover: Double? = null,
    val carryoverExpires: String? = null,
    val kindKrankCap: Int? = null,
)

// ---------------------------------------------------------------------------
// Enums (mirror backend domain values)
// ---------------------------------------------------------------------------

enum class TodoStatus { INBOX, PLANNED, DONE }
enum class Priority { LOW, MEDIUM, HIGH }
enum class Visibility { PRIVATE, SHARED }
enum class RecipeCategory { BREAKFAST, LUNCH, DINNER, SNACK, DESSERT, DRINK }
enum class AbsenceType { URLAUB, KRANK, KIND_KRANK }
