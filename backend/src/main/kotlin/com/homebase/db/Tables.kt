package com.homebase.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time

// Generic household-level key/value settings (#100). Currently holds the editable
// household name under key 'household_name'; reusable for future shared settings.
object AppSettingsTable : Table("app_settings") {
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)

    // Shared setting key so the route that writes it and the scheduler that reads it
    // can never drift apart (#100).
    const val DIGEST_TIME = "digest_time"

    // Daily send time of the morning briefing (due today / overdue / inbox / absences /
    // kita), editable in-app like the evening digest time; the scheduler re-reads it each
    // cycle so a change needs no restart.
    const val MORNING_DIGEST_TIME = "morning_digest_time"

    // Daily run time of the recurring-todo safety-net scheduler, editable in-app like the
    // digest time (#100); the scheduler re-reads it each cycle so a change needs no restart.
    const val RECURRING_TIME = "recurring_time"

    // Per-digest on/off + content-section selection (#182), all editable in-app and re-read by
    // the scheduler each cycle (like the times). The *_ENABLED keys hold "true"/"false"; the
    // *_SECTIONS keys hold a compact CSV of selected section ids (DigestSection.id). An unset
    // key means "use the default" (enabled = on; sections = all of that digest's sections), so a
    // fresh DB keeps sending the full digest exactly as before.
    const val DIGEST_EVENING_ENABLED = "digest_evening_enabled"
    const val DIGEST_MORNING_ENABLED = "digest_morning_enabled"
    const val DIGEST_EVENING_SECTIONS = "digest_evening_sections"
    const val DIGEST_MORNING_SECTIONS = "digest_morning_sections"

    // Todo reminders (#429 Phase 2a), all editable in-app and re-read by the scheduler each tick.
    // REMINDERS_ENABLED holds "true"/"false" (unset = on, like the digests). The quiet-hours window
    // holds "HH:mm" bounds; when both are set, reminders due inside the window are held until it
    // ends. An unset/partial window means no quiet hours.
    const val REMINDERS_ENABLED = "reminders_enabled"
    const val REMINDER_QUIET_START = "reminder_quiet_start"
    const val REMINDER_QUIET_END = "reminder_quiet_end"

    // How many calendar days the clients' "Erledigt" history window spans (#356, follows #340).
    // Editable in-app like the digest time; the clients read it (falling back to the code default
    // when unset) and apply it to the Erledigt tab / done-section. The per-device "Alle anzeigen"
    // toggle (#340) still overrides this to reveal the full history. Stored as a plain integer
    // string (e.g. "14"); the badge/tile COUNTS stay on "today" and ignore this value.
    const val DONE_WINDOW_DAYS = "done_window_days"
}

// Generic PER-USER key/value preferences (#100). Personal (each user reads/writes
// only their own rows), in contrast to the household-shared AppSettingsTable.
// Reusable for future prefs without a migration each. userId is the username
// (FK users.username; the FK + ON DELETE CASCADE live in V22, like every other
// user_id column in this file), composite PK (user_id, key) mirrors AbsSettings.
// First key: 'theme'.
object UserPrefsTable : Table("user_prefs") {
    val userId = varchar("user_id", 50)
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(userId, key)

    // First consumer: the UI theme. Kept here so the writer (route) and any future
    // reader can't drift on the key name.
    const val THEME = "theme"

    // Which categories a user's iCal subscription feed includes (#427): a compact CSV of
    // CalendarFeedSection ids. Per-user so each subscriber tailors their own feed; unset = all
    // (back-compat with the pre-toggle feed). Written via /config/calendar-feed, read by the feed.
    const val CALENDAR_FEED_SECTIONS = "calendar_feed_sections"
}

object UsersTable : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 50)
    val passwordHash = text("password_hash")
    val createdAt = timestamp("created_at")
    // Per-user avatar hue override (0..359), nullable (Teil von #100). NULL = automatic:
    // the client derives a stable hue from the username hash (#160). Household-visible on
    // purpose — exposed via GET /users so the partner sees the chosen colour (V23).
    val avatarHue = integer("avatar_hue").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TodoListsTable : Table("todo_lists") {
    val id = uuid("id")
    val name = text("name")
    val visibility = varchar("visibility", 10)
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object TodosTable : Table("todos") {
    val id = uuid("id")
    val title = text("title")
    val description = text("description").nullable()
    val status = varchar("status", 20)
    // Assignees moved to the todo_assignees join table (V39) — a todo can be assigned to any
    // subset of the household. See TodoAssigneesTable.
    val dueDate = date("due_date").nullable()
    // Optional time-of-day on the due date (#429) + optional reminder lead in minutes. Both require
    // a due_date (DB CHECK); reminder_lead is plumbed for the later notification work.
    val dueTime = time("due_time").nullable()
    val reminderLeadMinutes = integer("reminder_lead_minutes").nullable()
    val priority = varchar("priority", 10).nullable()
    val listId = uuid("list_id").nullable()
    // Recurrence: frequency DAILY|WEEKLY|MONTHLY + every-N interval; both NULL = one-off.
    val recurrence = varchar("recurrence", 10).nullable()
    val recurrenceInterval = integer("recurrence_interval").nullable()
    val createdBy = varchar("created_by", 50)
    // created_at/updated_at both carry a DB-level default mirroring the migrations' `DEFAULT NOW()`,
    // so the Exposed-built schema (H2 unit tests via SchemaUtils.create) stays faithful and fills them
    // when an insert omits the column. Prod/route inserts set both explicitly, overriding the default;
    // tests that predate a column rely on it.
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    val doneAt = timestamp("done_at").nullable()
    // Fire-once bookkeeping for the reminder scheduler (#429 Phase 2a): set when a reminder was
    // delivered/retired; NULL = not yet reminded (re-armed when the due moment is edited).
    val reminderSentAt = timestamp("reminder_sent_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Assignees of a todo (join table, V39). A todo can be assigned to any subset of the household
 * (zero, one, or several users); "both" is simply two rows. Replaces the former single
 * todos.assignee column. `username` FKs users(username); the pair is unique (composite PK).
 */
object TodoAssigneesTable : Table("todo_assignees") {
    val todoId = uuid("todo_id")
    val username = varchar("username", 50)
    override val primaryKey = PrimaryKey(todoId, username)
}

object TodoSubtasksTable : Table("todo_subtasks") {
    val id = uuid("id")
    val todoId = uuid("todo_id")
    val title = text("title")
    val done = bool("done")
    val sortOrder = integer("sort_order")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ShoppingListsTable : Table("shopping_lists") {
    val id = uuid("id")
    val name = text("name")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    // Per-list category set (#412): when true this list uses its OWN categories (the
    // shopping_categories rows tagged with its id) instead of the shared household catalog (#411).
    val ownCategories = bool("own_categories").default(false)
    override val primaryKey = PrimaryKey(id)
}

object ShoppingItemsTable : Table("shopping_items") {
    val id = uuid("id")
    val name = text("name")
    val listId = uuid("list_id").nullable()
    val checked = bool("checked")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    val checkedAt = timestamp("checked_at").nullable()
    // Resolved grocery category key + emoji icon (#389/#390), a denormalized cache of GroceryCatalog
    // resolution; nullable for legacy rows. Overridable per item (PUT), remembered in the stats table.
    val category = varchar("category", 40).nullable()
    val icon = varchar("icon", 32).nullable()
    // Free-text item details (#445): quantity ("500 g", "2×") + note ("im roten Glas"). Additive —
    // the batch flow still encodes the amount in the name; clients prefer `quantity` if present.
    val quantity = varchar("quantity", 120).nullable()
    val note = text("note").nullable()
    override val primaryKey = PrimaryKey(id)
}

// Per-name usage tally for the shopping autocomplete ("most used", #389/#390) plus remembered
// category/icon corrections. Keyed by the normalized item name (GroceryCatalog.normalize) so it
// outlives item deletion / clear-checked. Scoped per list (#501, V42): an own-categories list (#412)
// gets its own scope (its id); every shared list + the unfiled bucket share the all-zeros sentinel
// (ShoppingCatalog.SHARED_STATS_SCOPE). The composite PK (name, scope) keeps corrections + "most used"
// separate per scope while a real list id can never collide with the sentinel.
object ShoppingItemStatsTable : Table("shopping_item_stats") {
    val normalizedName = varchar("normalized_name", 200)
    val listScope = uuid("list_scope")
    val displayName = text("display_name")
    val category = varchar("category", 40).nullable()
    val icon = varchar("icon", 32).nullable()
    val useCount = integer("use_count")
    val lastUsedAt = timestamp("last_used_at")
    override val primaryKey = PrimaryKey(normalizedName, listScope)
}

// Editable grocery category catalog (#411): the category list moved from code (GroceryCatalog) into
// the DB so the household can manage its own categories (add/rename/emoji/reorder/delete). Seeded from
// GroceryCatalog.categories into the empty table on first startup (SEED_USERS-style). `is_builtin`
// flags the seeded ones (informational); OTHER stays the protected fallback. Items keep a denormalized
// category key (V29) referencing key here; a deleted category's items are reassigned to OTHER.
object ShoppingCategoriesTable : Table("shopping_categories") {
    val key = varchar("key", 40)
    val label = text("label")
    val emoji = varchar("emoji", 32)
    val sortOrder = integer("sort_order")
    val isBuiltin = bool("is_builtin")
    // Category scope (#412): NULL = the shared household catalog (#411, all pre-#412 rows); a list id =
    // that list's own categories. `key` stays the globally unique PK; OTHER stays the single shared row.
    // Plain nullable uuid like ShoppingItemsTable.listId — the FK + ON DELETE CASCADE live in the
    // migration (Postgres); the list-DELETE route cascades explicitly for the H2 test DB.
    val listId = uuid("list_id").nullable()
    override val primaryKey = PrimaryKey(key)
}

// Editable auto-resolve dictionary (#411 PR B): normalized item name → category key + emoji icon.
// Seeded from GroceryCatalog.seed; drives the DB-backed ShoppingCatalog.resolve. Keyed by the
// normalized name (GroceryCatalog.normalize). `category` is a denormalized shopping_categories key.
object ShoppingCategoryRulesTable : Table("shopping_category_rules") {
    val normalizedName = varchar("normalized_name", 200)
    // Per-list scope (#501, V43), same convention as ShoppingItemStatsTable: the all-zeros sentinel
    // (ShoppingCatalog.SHARED_STATS_SCOPE) = the shared household dictionary; a list id = that
    // own-categories list's (#412) private rules. Composite PK so a name resolves once per scope.
    val listScope = uuid("list_scope")
    val displayName = text("display_name")
    val category = varchar("category", 40)
    val icon = varchar("icon", 32)
    override val primaryKey = PrimaryKey(normalizedName, listScope)
}

// Named "standard/template shopping lists" (#215): a saved, reusable list of item names the
// household re-adds for the recurring shop. Shared like the shopping lists themselves — both
// users manage all templates. Items are embedded children (1:n), saved with the template,
// mirroring how a recipe owns its ingredients.
object ShoppingTemplatesTable : Table("shopping_templates") {
    val id = uuid("id")
    val name = text("name")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ShoppingTemplateItemsTable : Table("shopping_template_items") {
    val id = uuid("id")
    val templateId = reference("template_id", ShoppingTemplatesTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(id)
}

object ProjectsTable : Table("projects") {
    val id = uuid("id")
    val name = text("name")
    val color = varchar("color", 7)
    val archived = bool("archived")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object TimeEntriesTable : Table("time_entries") {
    val id = uuid("id")
    val projectId = uuid("project_id")
    val userId = varchar("user_id", 50)
    val startedAt = timestamp("started_at")
    val stoppedAt = timestamp("stopped_at").nullable()
    val description = text("description").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TimeWorkTargetsTable : Table("time_work_targets") {
    val id = uuid("id")
    val userId = varchar("user_id", 50)
    val projectId = uuid("project_id")
    // Contracted hours per ISO week on this project; 0 = no target (#31).
    val weeklyHours = double("weekly_hours")
    // The person's one default project — absence/holiday credits are booked here.
    // Uniqueness per user is enforced app-side + by a partial index in V20.
    val isDefault = bool("is_default")
    override val primaryKey = PrimaryKey(id)

    init { uniqueIndex("time_work_targets_user_project_uniq", userId, projectId) }
}

object AbsencesTable : Table("absences") {
    val id = uuid("id")
    val userId = varchar("user_id", 50)
    val date = date("date")
    val type = varchar("type", 20)
    val half = varchar("half", 2).nullable()
    override val primaryKey = PrimaryKey(id)
}

object PartTimeRulesTable : Table("part_time_rules") {
    val id = uuid("id")
    val userId = varchar("user_id", 50)
    val weekday = integer("weekday")
    val startDate = date("start_date")
    val endDate = date("end_date").nullable()
    override val primaryKey = PrimaryKey(id)
}

object KitaClosuresTable : Table("kita_closures") {
    val id = uuid("id")
    val date = date("date")
    val label = text("label")
    override val primaryKey = PrimaryKey(id)

    // One closure per date — mirrors the unique index from V8 (a closure is a
    // household-wide marker, so a second row for the same day is meaningless).
    init { uniqueIndex("kita_closures_date_uniq", date) }
}

object CustomHolidaysTable : Table("custom_holidays") {
    val id = uuid("id")
    // Recurring by fixed calendar date: month 1–12, day 1–31. No year — applies every year.
    val month = integer("month")
    val day = integer("day")
    // true = half day (counts as 0.5 toward the work target, see #31); false = whole day.
    val half = bool("half")
    val label = text("label")
    override val primaryKey = PrimaryKey(id)

    // One holiday per (month, day) — mirrors the unique index from V19 (a household-wide
    // marker, so a second row for the same calendar date is meaningless).
    init { uniqueIndex("custom_holidays_month_day_uniq", month, day) }
}

object AbsSettingsTable : Table("abs_settings") {
    val userId = varchar("user_id", 50)
    // One row per (user, year): allowance/carryover/expiry are inherently annual.
    val year = integer("year")
    val state = varchar("state", 2)
    val allowance = double("allowance")
    val carryover = double("carryover")
    val carryoverExpires = date("carryover_expires").nullable()
    val kindKrankCap = integer("kind_krank_cap")
    override val primaryKey = PrimaryKey(userId, year)
}

object NotesTable : Table("notes") {
    val id = uuid("id")
    val title = text("title")
    val content = text("content")
    // tags stored as a comma-separated string for portability (Postgres + H2 test DB)
    val tags = text("tags")
    // single-level folder label (issue #30); NULL = no folder
    val folder = varchar("folder", 100).nullable()
    val visibility = varchar("visibility", 10)
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object NoteImagesTable : Table("note_images") {
    val id = uuid("id")
    val noteId = reference("note_id", NotesTable.id, onDelete = ReferenceOption.CASCADE)
    // name of the file on disk (e.g. "<uuid>.jpg"); the original bytes are stored
    // outside the DB under the configured upload directory.
    val filename = text("filename")
    val originalName = text("original_name")
    val contentType = varchar("content_type", 100)
    val sizeBytes = long("size_bytes")
    val sortOrder = integer("sort_order")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// Arbitrary file attachments on a note (#431) — PDFs, office docs, text, … Separate from
// NoteImagesTable so images keep their inline markdown rendering + thumbnails while attachments
// are download-only. Same on-disk storage (UPLOAD_DIR) as note_images; the row holds metadata.
// content_type is wider (150) than the image table's 100 to fit the longer office MIME types.
object NoteAttachmentsTable : Table("note_attachments") {
    val id = uuid("id")
    val noteId = reference("note_id", NotesTable.id, onDelete = ReferenceOption.CASCADE)
    // name of the file on disk (e.g. "<uuid>.pdf"); the original bytes are stored
    // outside the DB under the configured upload directory.
    val filename = text("filename")
    val originalName = text("original_name")
    val contentType = varchar("content_type", 150)
    val sizeBytes = long("size_bytes")
    val sortOrder = integer("sort_order")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object RecipesTable : Table("recipes") {
    val id = uuid("id")
    val title = text("title")
    val description = text("description").nullable()
    val servings = integer("servings")
    val prepTimeMinutes = integer("prep_time_minutes").nullable()
    val cookTimeMinutes = integer("cook_time_minutes").nullable()
    val category = varchar("category", 20)
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object IngredientsTable : Table("ingredients") {
    val id = uuid("id")
    val recipeId = reference("recipe_id", RecipesTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val amount = decimal("amount", 12, 3).nullable()
    val unit = varchar("unit", 50).nullable()
    val section = text("section").nullable()
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(id)
}

object RecipeStepsTable : Table("recipe_steps") {
    val id = uuid("id")
    val recipeId = reference("recipe_id", RecipesTable.id, onDelete = ReferenceOption.CASCADE)
    val stepNumber = integer("step_number")
    val description = text("description")
    override val primaryKey = PrimaryKey(id)
}

// Wochenplan / Essensplaner (#218): one meal planned into a (date, slot) of the weekly grid.
// Household-wide shared (no owner check). `slot` is one of the three grid meals
// BREAKFAST|LUNCH|DINNER — intentionally independent of the recipe categories (no LUNCH since
// V17): any recipe can be planned into any slot. recipe_id cascades on recipe delete.
object MealPlanEntriesTable : Table("meal_plan_entries") {
    val id = uuid("id")
    val date = date("date")
    val slot = varchar("slot", 20)
    // A slot holds EITHER a recipe reference OR a free-text dish name (#293) — XOR, enforced by a
    // DB CHECK (V28). Free-text lets a slot hold a one-off meal ("Pizza bestellen") without
    // authoring a full recipe; such entries carry no ingredients (skipped by "In Einkaufsliste").
    val recipeId = reference("recipe_id", RecipesTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val dishTitle = text("dish_title").nullable()
    // Portions to cook (#251); NULL = use the recipe's own servings (1× as authored). Only
    // meaningful for recipe-backed entries (free-text has nothing to scale).
    val servings = integer("servings").nullable()
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    // At most one recipe per (date, slot) — mirrors the unique index from V26 (setting a slot
    // replaces the existing entry).
    init { uniqueIndex("meal_plan_entries_date_slot_uniq", date, slot) }
}

// Calendar events (#434): a real scheduled event (Arzt, Tierarzt, Geburtstag …) — the
// household calendar previously knew only todo due-dates / absences / kita / meal plan as an
// overlay. Household-wide shared like the absence calendar — no owner column / check;
// created_by is provenance only. all_day=TRUE events carry no time (a DB CHECK enforces it).
object CalendarEventsTable : Table("calendar_events") {
    val id = uuid("id")
    val title = varchar("title", 200)
    // Event kind for the (later) colour-coded calendar rendering. See EVENT_TYPES in EventRoutes.
    val type = varchar("type", 20)
    val date = date("date")
    val allDay = bool("all_day")
    // Optional clock time for non-all-day events; both NULL for all-day (DB CHECK), end without
    // start is rejected by the same migration's CHECK.
    val startTime = time("start_time").nullable()
    val endTime = time("end_time").nullable()
    val location = text("location").nullable()
    val notes = text("notes").nullable()
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init { index("calendar_events_date_idx", false, date) }
}

// One optional cover image per recipe — recipe_id is UNIQUE, so a new upload replaces the row.
object RecipeImagesTable : Table("recipe_images") {
    val id = uuid("id")
    val recipeId = reference("recipe_id", RecipesTable.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    // name of the file on disk (e.g. "<uuid>.jpg"); the original bytes are stored
    // outside the DB under the configured upload directory.
    val filename = text("filename")
    val originalName = text("original_name")
    val contentType = varchar("content_type", 100)
    val sizeBytes = long("size_bytes")
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// Browser Web Push subscriptions (#429 Phase 2b). One row per push endpoint a client
// registered via the Push API; the reminder scheduler delivers a push to every row.
// `endpoint` is the natural key (a re-subscribing browser returns the same endpoint → upsert);
// `p256dh`/`auth` are the client's base64url keys used to encrypt the payload. Rows the push
// service reports as gone (404/410) are pruned. See migration V38.
object PushSubscriptionsTable : Table("push_subscriptions") {
    val id = uuid("id")
    val endpoint = text("endpoint").uniqueIndex()
    val p256dh = text("p256dh")
    val auth = text("auth")
    val username = varchar("username", 50)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
