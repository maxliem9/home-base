package com.homebase.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val code: String, val message: String)

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class AppConfigResponse(val householdName: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(val token: String)

// The household members (2 fixed users, seeded from SEED_USERS). Clients use this to
// resolve "the other user" — e.g. to start/stop a partner's timer (#142) — since the
// usernames are configurable and not hard-codeable.
@Serializable
data class UserDto(val username: String)

@Serializable
data class SubtaskDto(
    val id: String,
    val title: String,
    val done: Boolean,
    val sortOrder: Int
)

/**
 * A lightweight recurrence rule on a todo (issue #44). [freq] is DAILY|WEEKLY|MONTHLY, [interval]
 * means "every N units" (default 1, omitted from the payload). On an UpdateTodoRequest a freq of
 * "NONE" clears the recurrence; on a response/create it is always one of the three frequencies.
 */
@Serializable
data class RecurrenceDto(
    val freq: String,
    val interval: Int = 1
)

@Serializable
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
    val doneAt: String? = null
)

@Serializable
data class CreateTodoRequest(
    val title: String,
    val description: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val listId: String? = null,
    val recurrence: RecurrenceDto? = null
)

@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    // null = unchanged; empty string = remove from list; otherwise the target list id
    val listId: String? = null,
    // null = unchanged; freq "NONE" clears it; otherwise sets/updates the rule
    val recurrence: RecurrenceDto? = null
)

@Serializable
data class WsMessage(val type: String, val payload: TodoDto? = null)

@Serializable
data class TodoListDto(
    val id: String,
    val name: String,
    val visibility: String,
    val createdBy: String,
    val createdAt: String
)

@Serializable
data class CreateTodoListRequest(
    val name: String,
    val visibility: String? = null
)

@Serializable
data class UpdateTodoListRequest(
    val name: String? = null,
    val visibility: String? = null
)

@Serializable
data class TodoListWsMessage(val type: String, val payload: TodoListDto? = null)

@Serializable
data class CreateSubtaskRequest(val title: String)

@Serializable
data class UpdateSubtaskRequest(
    val title: String? = null,
    val done: Boolean? = null
)

@Serializable
data class ShoppingItemDto(
    val id: String,
    val name: String,
    val listId: String? = null,
    val checked: Boolean,
    val createdBy: String,
    val createdAt: String,
    val checkedAt: String? = null
)

@Serializable
data class CreateShoppingItemRequest(
    val name: String,
    val listId: String? = null
)

@Serializable
data class UpdateShoppingItemRequest(
    val name: String? = null,
    // null = unchanged; empty string = remove from list; otherwise the target list id
    val listId: String? = null,
    val checked: Boolean? = null
)

@Serializable
data class ShoppingWsMessage(val type: String, val payload: ShoppingItemDto? = null)

/** One ingredient line for [BatchAddShoppingRequest] — amount is already scaled by the client. */
@Serializable
data class ShoppingLineInput(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null
)

/** Push several recipe ingredients onto a list at once; see the /shopping/batch route. */
@Serializable
data class BatchAddShoppingRequest(
    val listId: String? = null,
    val items: List<ShoppingLineInput> = emptyList()
)

/** Summary of a batch add: freshly created items, quantities merged into existing ones, skipped dupes. */
@Serializable
data class BatchAddShoppingResponse(
    val added: Int,
    val merged: Int,
    val skipped: Int,
    val items: List<ShoppingItemDto> = emptyList()
)

@Serializable
data class ShoppingListDto(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: String
)

@Serializable
data class CreateShoppingListRequest(val name: String)

@Serializable
data class UpdateShoppingListRequest(val name: String? = null)

@Serializable
data class ShoppingListWsMessage(val type: String, val payload: ShoppingListDto? = null)

@Serializable
data class NoteImageDto(
    val id: String,
    val noteId: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sortOrder: Int,
    val createdBy: String,
    val createdAt: String
)

@Serializable
data class NoteDto(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val visibility: String,
    // no default: the JSON config omits default values, but clients always expect
    // an `images` array (an empty one for image-less notes), so it must be encoded.
    val images: List<NoteImageDto>,
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

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val color: String,
    val archived: Boolean,
    val createdBy: String,
    val createdAt: String
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val color: String
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val color: String? = null
)

@Serializable
data class ArchiveProjectRequest(
    val archived: Boolean? = null
)

@Serializable
data class TimeEntryDto(
    val id: String,
    val projectId: String,
    val userId: String,
    val startedAt: String,
    val stoppedAt: String? = null,
    val description: String? = null,
    // seconds between started_at and stopped_at; null while the timer is still running
    val durationSeconds: Long? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StartTimerRequest(
    val projectId: String,
    val description: String? = null,
    // Optional target user (shared household, see #142): start the timer on behalf of
    // another household member. Null/absent → the calling user, as before.
    val userId: String? = null
)

@Serializable
data class StopTimerRequest(
    // Optional target user (shared household, see #142): stop another member's running
    // timer. Null/absent (incl. an empty body) → the calling user, as before.
    val userId: String? = null
)

@Serializable
data class CreateTimeEntryRequest(
    val projectId: String,
    val startedAt: String,
    val stoppedAt: String,
    val description: String? = null
)

@Serializable
data class UpdateTimeEntryRequest(
    val projectId: String? = null,
    val startedAt: String? = null,
    val stoppedAt: String? = null,
    val description: String? = null
)

@Serializable
data class TimeWsMessage(
    val type: String,
    val entry: TimeEntryDto? = null,
    val project: ProjectDto? = null
)

@Serializable
data class IngredientDto(
    val id: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    // optional group label, e.g. "Boden" / "Topping"; null = ungrouped (top section)
    val section: String? = null,
    val sortOrder: Int
)

@Serializable
data class RecipeStepDto(
    val id: String,
    val stepNumber: Int,
    val description: String
)

@Serializable
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
    val updatedAt: String
)

@Serializable
data class IngredientInput(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val section: String? = null
)

@Serializable
data class RecipeStepInput(
    val description: String
)

@Serializable
data class CreateRecipeRequest(
    val title: String,
    val description: String? = null,
    val servings: Int? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val category: String,
    val ingredients: List<IngredientInput> = emptyList(),
    val steps: List<RecipeStepInput> = emptyList()
)

@Serializable
data class UpdateRecipeRequest(
    val title: String? = null,
    val description: String? = null,
    val servings: Int? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val category: String? = null,
    // when provided, fully replaces the existing ingredients / steps
    val ingredients: List<IngredientInput>? = null,
    val steps: List<RecipeStepInput>? = null
)

@Serializable
data class RecipeWsMessage(val type: String, val payload: RecipeDto? = null)

// ---------- Abwesenheit / Familienkalender ----------

@Serializable
data class AbsenceDto(
    val id: String,
    val userId: String,
    val date: String,
    val type: String,
    val half: String? = null
)

@Serializable
data class PartTimeRuleDto(
    val id: String,
    val userId: String,
    val weekday: Int,
    val start: String,
    val end: String? = null
)

@Serializable
data class KitaClosureDto(
    val id: String,
    val date: String,
    val label: String
)

@Serializable
data class AbsSettingsDto(
    val userId: String,
    val year: Int,
    val state: String,
    val allowance: Double,
    val carryover: Double,
    val carryoverExpires: String? = null,
    val kindKrankCap: Int
)

/** Full snapshot of the planner — clients refetch this after any change. */
@Serializable
data class AbsenceStateDto(
    val users: List<String>,
    val absences: List<AbsenceDto>,
    val partTime: List<PartTimeRuleDto>,
    val kitaClosures: List<KitaClosureDto>,
    val settings: List<AbsSettingsDto>
)

@Serializable
data class SetAbsenceRequest(
    val userId: String,
    val date: String,
    val type: String,
    val half: String? = null
)

/** Bulk apply (or clear, when type is null) on the given dates. */
@Serializable
data class BatchAbsenceRequest(
    val userId: String,
    val type: String? = null,
    val half: String? = null,
    val dates: List<String> = emptyList()
)

@Serializable
data class CreatePartTimeRequest(
    val userId: String,
    val weekday: Int,
    val start: String,
    val end: String? = null
)

/** Full replace of a rule's fields — end = null means open-ended. */
@Serializable
data class UpdatePartTimeRequest(
    val weekday: Int,
    val start: String,
    val end: String? = null
)

@Serializable
data class CreateKitaRequest(
    val date: String,
    val label: String? = null
)

@Serializable
data class CreateKitaRangeRequest(
    val from: String,
    val to: String,
    val label: String? = null
)

@Serializable
data class UpdateKitaRequest(
    val date: String? = null,
    val label: String? = null
)

@Serializable
data class UpdateAbsSettingsRequest(
    val state: String? = null,
    val allowance: Double? = null,
    val carryover: Double? = null,
    val carryoverExpires: String? = null,
    val kindKrankCap: Int? = null
)

@Serializable
data class AbsenceWsMessage(val type: String)
