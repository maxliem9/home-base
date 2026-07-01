package com.homebase.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val code: String, val message: String)

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class AppConfigResponse(val householdName: String)

@Serializable
data class UpdateConfigRequest(val householdName: String)

// Telegram digest config (#100, extended #182). Per-digest and independent of the morning/
// evening sibling:
//  - time: HH:mm send time.
//  - enabled: the in-app on/off toggle (#182) — distinct from whether Telegram is wired up.
//  - telegramConfigured: whether bot token + chat id are set server-side; a digest only actually
//    sends when both enabled AND configured. The UI keeps the controls editable regardless and
//    shows an "inactive" note when not configured.
//  - sections: the section ids this digest currently renders (the user's selection).
//  - availableSections: all section ids this digest can render, in display order (drives the
//    checkbox group + their labels client-side).
@Serializable
data class DigestConfigResponse(
    val time: String,
    val enabled: Boolean,
    val telegramConfigured: Boolean,
    val sections: List<String>,
    val availableSections: List<String>,
)

// All fields optional so a client can patch just the time, just the toggle, or just the
// sections (the old time-only PUT still works). null = leave unchanged.
@Serializable
data class UpdateDigestRequest(
    val time: String? = null,
    val enabled: Boolean? = null,
    val sections: List<String>? = null,
)

// Recurring-todo safety-net run time (#100). Always-on scheduler (no Telegram-style
// `enabled` flag), so just the editable HH:mm time. Mirrors the digest-time shape.
@Serializable
data class RecurringConfigResponse(val time: String)

@Serializable
data class UpdateRecurringRequest(val time: String)

// Todo reminders config (#429 Phase 2a). `enabled` (default true) + an optional quiet-hours
// window ("HH:mm" bounds, both or neither); during the window reminders are held. The PUT mirrors
// the response; quietStart/quietEnd null/blank clears the window. encodeDefaults=false omits nulls.
@Serializable
data class RemindersConfigResponse(
    val enabled: Boolean,
    val quietStart: String? = null,
    val quietEnd: String? = null,
)

@Serializable
data class UpdateRemindersRequest(
    val enabled: Boolean,
    val quietStart: String? = null,
    val quietEnd: String? = null,
)

// "Erledigt"-history window length in calendar days (#356, follows #340). Household-wide,
// stored in app_settings; the clients read it (default 14 when unset) and apply it to the
// Erledigt tab / done-section. Mirrors the recurring-time single-value shape. The per-device
// "Alle anzeigen" toggle (#340) overrides this; the badge/tile counts stay on "today".
@Serializable
data class DoneWindowConfigResponse(val days: Int)

@Serializable
data class UpdateDoneWindowRequest(val days: Int)

// Which categories the caller's iCal subscription feed includes (#427). PER USER (stored in
// user_prefs), so each subscriber tailors their own feed; an untouched account gets all categories.
//  - sections: the category ids currently included (the user's selection).
//  - availableSections: every category the feed can include, in display order (drives the checkbox
//    group + labels client-side). Mirrors the digest {sections, availableSections} shape.
@Serializable
data class CalendarFeedConfigResponse(
    val sections: List<String>,
    val availableSections: List<String>,
)

@Serializable
data class UpdateCalendarFeedRequest(val sections: List<String>)

// Per-user preference write (#100). The key is in the path; this is just the value.
// GET /user-prefs returns a plain Map<String, String> (no wrapper DTO) so new keys
// surface without a model change. First consumer: 'theme' (light|dark|system).
@Serializable
data class UpdateUserPrefRequest(val value: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(val token: String)

// The household members (2 fixed users, seeded from SEED_USERS). Clients use this to
// resolve "the other user" — e.g. to start/stop a partner's timer — since the
// usernames are configurable and not hard-codeable.
//
// avatarHue (Teil von #100): the user's chosen avatar hue (0..359), or null/absent for
// "automatic" (client derives a stable hue from the username hash, #160). Household-
// visible on purpose — the partner must see your colour — which is why it rides on this
// shared roster rather than the own-read-only user_prefs. encodeDefaults=false (#46)
// omits the field when null, so clients must tolerate its absence.
@Serializable
data class UserDto(val username: String, val avatarHue: Int? = null)

// Set the authenticated user's own avatar hue (Teil von #100). hue in 0..359, or null to
// clear back to automatic/derived. Personal (own-only via /users/me), unlike the
// deliberately household-shared calendars.
@Serializable
data class SetAvatarColorRequest(val hue: Int? = null)

@Serializable
data class SubtaskDto(
    val id: String,
    val title: String,
    val done: Boolean,
    val sortOrder: Int
)

/**
 * A lightweight recurrence rule on a todo. [freq] is DAILY|WEEKLY|MONTHLY, [interval]
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
    // Optional time-of-day on the due date, "HH:mm" (#429). Reminder lead = minutes before due;
    // plumbed for the later notification work, no scheduler reads it yet.
    val dueTime: String? = null,
    val reminderLeadMinutes: Int? = null,
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
    // "HH:mm" time-of-day + minutes-before reminder (#429); both require a dueDate.
    val dueTime: String? = null,
    val reminderLeadMinutes: Int? = null,
    val priority: String? = null,
    val listId: String? = null,
    val recurrence: RecurrenceDto? = null
)

@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    // null = unchanged; empty string = clear (set to null); otherwise the new value (#265)
    val assignee: String? = null,
    val dueDate: String? = null,
    // dueTime: null = unchanged, "" = clear, "HH:mm" = set (#265 convention, like dueDate).
    val dueTime: String? = null,
    // reminderLeadMinutes: null = unchanged, negative = clear, >= 0 = set (the int analogue of #265).
    val reminderLeadMinutes: Int? = null,
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
    val checkedAt: String? = null,
    // Resolved grocery category key (e.g. "PRODUCE") + emoji icon (#389/#390); null on legacy rows,
    // which clients treat as the "Sonstiges"/OTHER bucket with a default icon.
    val category: String? = null,
    val icon: String? = null,
    // Free-text item details (#445): quantity ("500 g", "2×") + note ("im roten Glas"); null if unset.
    val quantity: String? = null,
    val note: String? = null
)

/** One autocomplete suggestion: a known/previously-added grocery item, ranked by how often it's been added. */
@Serializable
data class ShoppingSuggestionDto(
    val name: String,
    val category: String,
    val icon: String,
    val count: Int
)

@Serializable
data class CreateShoppingItemRequest(
    val name: String,
    val listId: String? = null,
    // Optional free-text quantity set at add time (#445); the web quick-add splits "200 g Mehl" into
    // name "Mehl" + quantity "200 g". Blank = unset.
    val quantity: String? = null
)

@Serializable
data class UpdateShoppingItemRequest(
    val name: String? = null,
    // null = unchanged; empty string = remove from list; otherwise the target list id
    val listId: String? = null,
    val checked: Boolean? = null,
    // Manual category/icon override (#389/#390). null/blank = unchanged; a non-blank category must be
    // a known key (else 400). Setting either remembers the choice in shopping_item_stats for next time.
    val category: String? = null,
    val icon: String? = null,
    // Free-text item details (#445). null = unchanged; empty string = clear; otherwise the new value.
    val quantity: String? = null,
    val note: String? = null
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

// ---------- Shopping categories (editable catalog, #411) ----------
// The grocery category LIST moved from the hardcoded GroceryCatalog into shopping_categories so the
// household can manage its own categories. `key` is the stable id stored on items; it is generated
// from the label on create and never changes. `isBuiltin` flags the seeded set (info only — builtins
// are editable AND deletable, except OTHER which stays the protected fallback).
@Serializable
data class ShoppingCategoryDto(
    val key: String,
    val label: String,
    val emoji: String,
    val sortOrder: Int,
    val isBuiltin: Boolean,
)

@Serializable
data class CreateShoppingCategoryRequest(
    val label: String,
    val emoji: String,
    val sortOrder: Int? = null,
)

@Serializable
data class UpdateShoppingCategoryRequest(
    val label: String? = null,
    val emoji: String? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class ShoppingCategoryWsMessage(val type: String, val payload: ShoppingCategoryDto? = null)

// ---------- Shopping category rules (editable auto-resolve dictionary, #411 PR B) ----------
// Maps a written item name → category key + emoji that newly added items auto-fill. Keyed by the
// normalized name (server-derived from displayName via GroceryCatalog.normalize). PUT upserts a rule;
// DELETE /{name} removes it. `icon` defaults to the neutral cart on create when omitted.
@Serializable
data class ShoppingCategoryRuleDto(
    val normalizedName: String,
    val displayName: String,
    val category: String,
    val icon: String,
)

@Serializable
data class UpsertCategoryRuleRequest(
    val displayName: String,
    val category: String,
    val icon: String? = null,
)

@Serializable
data class ShoppingCategoryRuleWsMessage(val type: String, val payload: ShoppingCategoryRuleDto? = null)

// ---------- Shopping templates (#215) ----------
// A named "standard list": a saved set of item names the household re-adds for the recurring
// shop. Items are embedded (always returned with the template, ordered by sortOrder), like a
// recipe embeds its ingredients. Applying a template is a client concern (reuses /shopping/batch),
// so there is no apply endpoint and no quantity/unit here — just names.

@Serializable
data class ShoppingTemplateItemDto(
    val id: String,
    val name: String,
    val sortOrder: Int
)

@Serializable
data class ShoppingTemplateDto(
    val id: String,
    val name: String,
    // embedded children, ordered by sortOrder; emptyList() default omitted from the payload (#46)
    val items: List<ShoppingTemplateItemDto> = emptyList(),
    val createdBy: String,
    val createdAt: String
)

/** One item name for a template create/update request — names only, no amount/unit. */
@Serializable
data class TemplateItemInput(val name: String)

@Serializable
data class CreateShoppingTemplateRequest(
    val name: String,
    val items: List<TemplateItemInput> = emptyList()
)

/** Update replaces the name and the full item set (wholesale, like a recipe update). */
@Serializable
data class UpdateShoppingTemplateRequest(
    val name: String? = null,
    // when provided, fully replaces the existing items
    val items: List<TemplateItemInput>? = null
)

@Serializable
data class ShoppingTemplateWsMessage(val type: String, val payload: ShoppingTemplateDto? = null)

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

// Arbitrary (non-rendered) file attachment on a note (#431). Same shape as NoteImageDto;
// distinct type so clients can render images as thumbnails and attachments as download chips.
@Serializable
data class NoteAttachmentDto(
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
    // single-level folder label (issue #30); null/omitted = no folder
    val folder: String? = null,
    val visibility: String,
    // no default: the JSON config omits default values, but clients always expect
    // an `images` array (an empty one for image-less notes), so it must be encoded.
    val images: List<NoteImageDto>,
    // likewise always encoded so clients can normalise to [] (#431). encodeDefaults=false would
    // otherwise drop an empty list, breaking the "always an array" contract for attachments too.
    val attachments: List<NoteAttachmentDto>,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CreateNoteRequest(
    val title: String,
    val content: String? = null,
    val tags: List<String>? = null,
    val folder: String? = null,
    val visibility: String? = null
)

@Serializable
data class UpdateNoteRequest(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val folder: String? = null,
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
    // Optional target user (shared household): start the timer on behalf of
    // another household member. Null/absent → the calling user, as before.
    val userId: String? = null
)

@Serializable
data class StopTimerRequest(
    // Optional target user (shared household): stop another member's running
    // timer. Null/absent (incl. an empty body) → the calling user, as before.
    val userId: String? = null
)

@Serializable
data class CreateTimeEntryRequest(
    val projectId: String,
    val startedAt: String,
    val stoppedAt: String,
    val description: String? = null,
    // Optional target user (shared household): record the entry on behalf of
    // another household member. Null/absent → the calling user, as before.
    val userId: String? = null
)

@Serializable
data class UpdateTimeEntryRequest(
    val projectId: String? = null,
    val startedAt: String? = null,
    val stoppedAt: String? = null,
    val description: String? = null
)

/**
 * Split a completed entry at [splitAt] into two (#62) — e.g. a forgotten lunch
 * break or a missed project switch. [breakMinutes] inserts a gap after the cut:
 * the second part starts that much later (the break is just untracked time, no
 * row of its own).
 */
@Serializable
data class SplitTimeEntryRequest(
    val splitAt: String,
    val breakMinutes: Int? = null
)

/** Both halves of a split: [first] keeps the original id, [second] is new. */
@Serializable
data class SplitTimeEntryResponse(
    val first: TimeEntryDto,
    val second: TimeEntryDto
)

@Serializable
data class TimeWsMessage(
    val type: String,
    val entry: TimeEntryDto? = null,
    val project: ProjectDto? = null,
    // set on TARGET_UPDATED frames (#31)
    val target: WorkTargetDto? = null
)

// ---------- Wochensoll & Forecast (#31) ----------

/** Weekly work-hour target of one person on one project. */
@Serializable
data class WorkTargetDto(
    val userId: String,
    val projectId: String,
    val weeklyHours: Double,
    // the person's one default project: absence/holiday credits are booked here
    val isDefault: Boolean
)

/** Partial upsert; absent fields keep their current (or initial: 0h / false) value. */
@Serializable
data class UpsertWorkTargetRequest(
    val weeklyHours: Double? = null,
    val isDefault: Boolean? = null
)

/**
 * Server-computed forecast for one ISO week (Mon–Sun): per person the redistributed
 * daily target, the projected end of the current working day and the week's
 * over/under balance. All second values are rounded; "remaining" values are signed
 * (negative = already over target).
 */
@Serializable
data class TimeForecastDto(
    // local date (server zone) the day values refer to
    val date: String,
    // Monday of the ISO week containing [date]
    val weekStart: String,
    val users: List<UserForecastDto> = emptyList()
)

@Serializable
data class UserForecastDto(
    val userId: String,
    // configured weekly target (sum over all project targets)
    val weeklyTargetHours: Double,
    // Mon–Fri minus the person's part-time-free days (holidays/absences do NOT reduce it)
    val workdayCount: Double,
    val weekTargetSeconds: Long,
    // recorded entries whose start date falls into the week, incl. a running timer's elapsed
    val weekRecordedSeconds: Long,
    // absence/holiday credits over the whole week (full day = daily target, half = 0.5×)
    val weekCreditedSeconds: Long,
    val weekRemainingSeconds: Long,
    // today's share after redistributing the week's remainder over the remaining recordable days
    val todayTargetSeconds: Long,
    val todayRecordedSeconds: Long,
    val todayRemainingSeconds: Long,
    // projected stop time while a timer is running (never in the past); null otherwise
    val expectedEndAt: String? = null,
    val projects: List<ProjectForecastDto> = emptyList()
)

/** Week balance of one project that has a target (or recorded time) this week. */
@Serializable
data class ProjectForecastDto(
    val projectId: String,
    val weeklyHours: Double,
    val recordedSeconds: Long,
    // Credits land on the person's default project only. Without a default project
    // they still count at the person level (weekCredited/-Remaining) but appear on
    // no project — the project saldi then don't add up to the person's saldo.
    val creditedSeconds: Long,
    // recorded + credited − target (negative = behind)
    val deltaSeconds: Long
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
data class RecipeImageDto(
    val id: String,
    val recipeId: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdBy: String,
    val createdAt: String
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
    // optional single cover image (null/omitted when the recipe has none)
    val image: RecipeImageDto? = null,
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

// --- URL-Import (schema.org/Recipe JSON-LD), Issue #430 -----------------------------------
// Request body for POST /recipes/import.
@Serializable
data class ImportRecipeRequest(val url: String)

// A best-effort recipe DRAFT extracted from a page's JSON-LD. NOT persisted — the client
// pre-fills its editor with this and the user reviews/edits before saving. Mirrors the editable
// fields of CreateRecipeRequest (no id/createdBy/timestamps). `sourceUrl` is echoed back so the
// client can show where it came from. Fields are best-effort: anything the page didn't provide
// stays null/empty and the user fills it in.
@Serializable
data class RecipeDraftDto(
    val title: String,
    val description: String? = null,
    val servings: Int? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val category: String,
    val ingredients: List<IngredientInput> = emptyList(),
    val steps: List<RecipeStepInput> = emptyList(),
    val sourceUrl: String? = null,
)

// ---------- Wochenplan / Essensplaner (#218) ----------
// One meal planned into a (date, slot) of the weekly grid; slot ∈ BREAKFAST|LUNCH|DINNER.
// Exactly one of recipeId / dishTitle is set (#293): recipe-backed entries also carry the joined
// recipe title/category so the grid renders without a second fetch; free-text entries carry only
// dishTitle. With encodeDefaults=false the unused fields are omitted from the payload.

@Serializable
data class MealPlanEntryDto(
    val id: String,
    val date: String,
    val slot: String,
    val recipeId: String? = null,
    val recipeTitle: String? = null,
    val recipeCategory: String? = null,
    // free-text dish name when no recipe is referenced (#293)
    val dishTitle: String? = null,
    // portions to cook (#251); null = use the recipe's own servings (1× as authored)
    val servings: Int? = null,
    val createdBy: String,
    val createdAt: String,
)

/**
 * Set/replace the meal in a (date, slot) — PUT /meal-plan/{date}/{slot}. Provide EITHER recipeId
 * (a real recipe) OR dishTitle (free text, #293), never both/neither. servings applies to recipes.
 */
@Serializable
data class SetMealPlanRequest(val recipeId: String? = null, val dishTitle: String? = null, val servings: Int? = null)

/** Any meal-plan mutation broadcasts this; clients refetch the visible range (like absence). */
@Serializable
data class MealPlanWsMessage(val type: String)

// ---------- Kalender-Events / Termine (#434) ----------

/**
 * A scheduled household calendar event (Arzt, Tierarzt, Geburtstag …). Household-shared like the
 * absence calendar. all_day=true events carry no time (start/end null). Times are "HH:mm" (or
 * "HH:mm:ss") strings; optional fields are omitted by encodeDefaults=false, so clients must
 * tolerate missing keys.
 */
@Serializable
data class CalendarEventDto(
    val id: String,
    val title: String,
    val type: String,
    val date: String,
    val allDay: Boolean,
    val startTime: String? = null,
    val endTime: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val createdBy: String,
    val createdAt: String,
)

/**
 * Create/replace a calendar event (POST /events, PUT /events/{id}). title + date required;
 * type defaults to OTHER. For all_day=false an optional startTime ("HH:mm") and optional endTime
 * may be given (end requires start, end >= start); all_day=true rejects any time. location/notes
 * are trimmed (blank -> null) and length-bounded server-side (400 if exceeded).
 */
@Serializable
data class CalendarEventRequest(
    val title: String,
    val type: String? = null,
    val date: String,
    val allDay: Boolean = true,
    val startTime: String? = null,
    val endTime: String? = null,
    val location: String? = null,
    val notes: String? = null,
)

/** Any event mutation broadcasts this; clients refetch the visible range (like meal-plan/absence). */
@Serializable
data class CalendarEventWsMessage(val type: String)

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

/**
 * A household-wide custom holiday (#51), recurring every year on a fixed [month]+[day].
 * [half] = true marks a half day (½ free; the other half stays a regular work/tracking
 * day — and counts as 0.5 toward the work target in #31). Heiligabend/Silvester are
 * seeded as half days; no user/Bundesland — it applies to everyone.
 */
@Serializable
data class CustomHolidayDto(
    val id: String,
    val month: Int,
    val day: Int,
    val half: Boolean,
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
    val users: List<String> = emptyList(),
    val absences: List<AbsenceDto> = emptyList(),
    val partTime: List<PartTimeRuleDto> = emptyList(),
    val kitaClosures: List<KitaClosureDto> = emptyList(),
    val customHolidays: List<CustomHolidayDto> = emptyList(),
    val settings: List<AbsSettingsDto> = emptyList()
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
data class CreateCustomHolidayRequest(
    val month: Int,
    val day: Int,
    val half: Boolean = false,
    val label: String? = null
)

/** Full replace of a custom holiday's fields; null = leave that field unchanged. */
@Serializable
data class UpdateCustomHolidayRequest(
    val month: Int? = null,
    val day: Int? = null,
    val half: Boolean? = null,
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
