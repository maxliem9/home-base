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

/** Body for PUT /config (#100/#101). The backend validates 1..60 chars. */
@JsonClass(generateAdapter = true)
data class UpdateConfigRequest(val householdName: String)

/** Body for PUT /users/me/password (#101). Backend checks the current password + min length 8. */
@JsonClass(generateAdapter = true)
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/**
 * GET/PUT /config/digest + /config/morning-digest (#101/#189). Each digest exposes its send
 * [time], an in-app on/off [enabled] flag (independent of Telegram), the read-only
 * [telegramConfigured] flag (whether anything actually sends — drives only the inactive hint),
 * the currently selected [sections] and all selectable [availableSections] in display order.
 * `sections`/`availableSections` are `= emptyList()` so a missing key (the `encodeDefaults=false`
 * convention) deserialises to an empty list rather than failing.
 */
@JsonClass(generateAdapter = true)
data class DigestConfigResponse(
    val time: String,
    val enabled: Boolean,
    val telegramConfigured: Boolean = false,
    val sections: List<String> = emptyList(),
    val availableSections: List<String> = emptyList(),
)

/**
 * Body for PUT /config/digest + /config/morning-digest. All fields optional so a client can
 * patch any subset ({time, enabled, sections}); the backend leaves omitted fields unchanged.
 * Sent fully populated from the settings screen's Save.
 */
@JsonClass(generateAdapter = true)
data class UpdateDigestRequest(
    val time: String? = null,
    val enabled: Boolean? = null,
    val sections: List<String>? = null,
)

/**
 * GET/PUT /config/recurring (#200): the recurring-todo safety-net scheduler's run [time]
 * (HH:mm, `app_settings.recurring_time`, default "00:30"). The scheduler is always-on, so —
 * unlike the digests — there's no enabled/sections, just the time. Serves as both the GET
 * response and the PUT body (the backend echoes the persisted, normalised time).
 */
@JsonClass(generateAdapter = true)
data class RecurringConfigResponse(val time: String)

// A household member. From GET /api/v1/users — used to resolve "the other user" for
// shared timers, since the usernames are configurable, not hard-codeable.
// avatarHue (Teil von #100): the member's chosen avatar hue (0..359), or null/absent for
// "automatic" (derive from the username hash, see Hb.userHue). Household-visible, so the
// same colours show on Android as on web. The key is omitted when null (encodeDefaults=
// false on the backend); the `= null` default makes a missing key parse to null.
@JsonClass(generateAdapter = true)
data class UserDto(val username: String, val avatarHue: Int? = null)

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

/** Recurrence rule on a todo. freq DAILY|WEEKLY|MONTHLY; on an update "NONE" clears it. */
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

// --- Shopping templates (named standard lists, #215) ---------------------------------------
// A template is just a name + a list of item names; applying one reuses the existing batch-add,
// so there is no amount/unit here. `items` is OMITTED by the backend when empty (encodeDefaults
// = false), hence `= emptyList()` so a missing key parses to an empty list (#46).

@JsonClass(generateAdapter = true)
data class ShoppingTemplateItemDto(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
)

@JsonClass(generateAdapter = true)
data class ShoppingTemplateDto(
    val id: String,
    val name: String,
    val items: List<ShoppingTemplateItemDto> = emptyList(),
    val createdBy: String,
    val createdAt: String,
)

/** One item name for a template create/update request — names only, no amount/unit. */
@JsonClass(generateAdapter = true)
data class TemplateItemInput(val name: String)

@JsonClass(generateAdapter = true)
data class CreateShoppingTemplateRequest(
    val name: String,
    val items: List<TemplateItemInput> = emptyList(),
)

/** Update: name and/or items. `items == null` leaves them unchanged; `[]` clears; else full replace. */
@JsonClass(generateAdapter = true)
data class UpdateShoppingTemplateRequest(
    val name: String? = null,
    val items: List<TemplateItemInput>? = null,
)

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
    // single-level folder label (issue #30/#45). The backend omits the key when unset
    // (encodeDefaults=false), so the default turns a missing key into null.
    val folder: String? = null,
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
    // blank ⇒ the backend trims and maps it to null (no folder)
    val folder: String? = null,
    val visibility: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateNoteRequest(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    // blank ⇒ clears the folder (backend trims and maps blank ⇒ null)
    val folder: String? = null,
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
    // Optional target user: start the timer on behalf of the partner. Null → self.
    val userId: String? = null,
)

@JsonClass(generateAdapter = true)
data class StopTimerRequest(
    // Optional target user: stop the partner's timer. Null → own timer.
    val userId: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateTimeEntryRequest(
    val projectId: String,
    val startedAt: String,
    val stoppedAt: String,
    val description: String? = null,
    // Optional target user (shared household): record the entry for the partner.
    // Null/absent → the calling user, mirrors /start and /stop.
    val userId: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTimeEntryRequest(
    val projectId: String? = null,
    val startedAt: String? = null,
    val stoppedAt: String? = null,
    val description: String? = null,
)

/** Split a completed entry at a cut time (#66); breakMinutes = untracked gap before part two. */
@JsonClass(generateAdapter = true)
data class SplitTimeEntryRequest(
    val splitAt: String,
    val breakMinutes: Int? = null,
)

/** Both halves of a split (#66): [first] keeps the original id, [second] is new. */
@JsonClass(generateAdapter = true)
data class SplitTimeEntryResponse(
    val first: TimeEntryDto,
    val second: TimeEntryDto,
)

@JsonClass(generateAdapter = true)
data class TimeWsMessage(
    val type: String,
    val entry: TimeEntryDto? = null,
    val project: ProjectDto? = null,
    val target: WorkTargetDto? = null,
)

// ---------- Wochensoll & Forecast (#31 / #55) ----------

/** Weekly work-hour target of one person on one project. */
@JsonClass(generateAdapter = true)
data class WorkTargetDto(
    val userId: String,
    val projectId: String,
    val weeklyHours: Double,
    // the person's one default project: absence/holiday credits are booked here
    val isDefault: Boolean,
)

/** Partial upsert; absent fields keep their current (or initial: 0h / false) value. */
@JsonClass(generateAdapter = true)
data class UpsertWorkTargetRequest(
    val weeklyHours: Double? = null,
    val isDefault: Boolean? = null,
)

/**
 * Server-computed forecast for the current ISO week (GET /api/v1/time/forecast).
 * "remaining" values are signed (negative = already over target). The backend
 * omits empty lists and null fields (encodeDefaults=false), so list fields
 * default to empty and optionals are nullable with defaults (issue #46).
 */
@JsonClass(generateAdapter = true)
data class TimeForecastDto(
    val date: String,
    val weekStart: String,
    val users: List<UserForecastDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class UserForecastDto(
    val userId: String,
    val weeklyTargetHours: Double,
    val workdayCount: Double,
    val weekTargetSeconds: Long,
    val weekRecordedSeconds: Long,
    val weekCreditedSeconds: Long,
    val weekRemainingSeconds: Long,
    val todayTargetSeconds: Long,
    val todayRecordedSeconds: Long,
    val todayRemainingSeconds: Long,
    // projected stop time while a timer runs (never in the past); omitted otherwise
    val expectedEndAt: String? = null,
    val projects: List<ProjectForecastDto> = emptyList(),
)

/** Week balance of one project that has a target (or recorded time) this week. */
@JsonClass(generateAdapter = true)
data class ProjectForecastDto(
    val projectId: String,
    val weeklyHours: Double,
    val recordedSeconds: Long,
    val creditedSeconds: Long,
    // recorded + credited − target (negative = behind)
    val deltaSeconds: Long,
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
data class RecipeImageDto(
    val id: String,
    val recipeId: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdBy: String,
    val createdAt: String,
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
    // optional single cover image; backend omits the key when the recipe has none
    val image: RecipeImageDto? = null,
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

/**
 * A household-wide custom holiday (#51), recurring every year on a fixed [month]+[day].
 * [half] = true marks a half day (½ free; the other half stays a regular work/tracking day).
 * No user/Bundesland — it applies to everyone. Rendered in the calendar and editable in the
 * absence settings (#243, mirroring the web `AbwSettings` holiday section).
 */
@JsonClass(generateAdapter = true)
data class CustomHolidayDto(
    val id: String,
    val month: Int,
    val day: Int,
    // defensive default: the backend has no default on `half` so it currently always serializes it;
    // tolerate it missing anyway (future-proof against an added default)
    val half: Boolean = false,
    val label: String,
)

@JsonClass(generateAdapter = true)
data class AbsSettingsDto(
    val userId: String,
    val year: Int, // settings are stored per calendar year
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
    val customHolidays: List<CustomHolidayDto> = emptyList(),
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
data class CreateCustomHolidayRequest(
    val month: Int,
    val day: Int,
    val half: Boolean = false,
    val label: String? = null,
)

/** Full replace of a custom holiday's fields; null = leave that field unchanged. */
@JsonClass(generateAdapter = true)
data class UpdateCustomHolidayRequest(
    val month: Int? = null,
    val day: Int? = null,
    val half: Boolean? = null,
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
enum class RecipeCategory { BREAKFAST, DINNER, SNACK, DESSERT, DRINK }
enum class AbsenceType { URLAUB, KRANK, KIND_KRANK }
