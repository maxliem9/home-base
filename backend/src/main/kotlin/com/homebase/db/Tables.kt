package com.homebase.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.date

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
    val assignee = varchar("assignee", 50).nullable()
    val dueDate = date("due_date").nullable()
    val priority = varchar("priority", 10).nullable()
    val listId = uuid("list_id").nullable()
    // Recurrence: frequency DAILY|WEEKLY|MONTHLY + every-N interval; both NULL = one-off.
    val recurrence = varchar("recurrence", 10).nullable()
    val recurrenceInterval = integer("recurrence_interval").nullable()
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    val doneAt = timestamp("done_at").nullable()
    override val primaryKey = PrimaryKey(id)
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
