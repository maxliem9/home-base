package com.homebase.service

import com.homebase.db.TodoAssigneesTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UsersTable
import com.homebase.model.*
import com.homebase.recurrence.Recurrence
import com.homebase.recurrence.RecurrenceSpawner
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import com.homebase.db.dbQuery
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

// The wire/DB visibility strings, sourced from the typed [ListVisibility] enum (#556) so there is one
// authority for the valid values. Shared by todo lists and notes (NoteService reads these).
internal val VISIBILITY_SHARED = ListVisibility.SHARED.name
internal val VISIBILITY_PRIVATE = ListVisibility.PRIVATE.name
private val DEFAULT_LIST_VISIBILITY = VISIBILITY_SHARED
private val SERVER_ZONE: ZoneId = ZoneId.systemDefault()

/**
 * A todo plus the visibility of its list before and after a mutation, so the route can pick the right
 * broadcast without re-touching the DB. [spawned] carries the next recurrence instance created when a
 * recurring todo is completed, to broadcast separately.
 */
data class TodoMutation(
    val todo: TodoDto,
    val wasShared: Boolean,
    val isShared: Boolean,
    val spawned: TodoDto? = null,
    val spawnedShared: Boolean = false,
)

/**
 * Owns the todo domain: validation, the tri-state (#265) merge, recurrence business rules, private-list
 * visibility (#73) and all Exposed persistence. Routes shrink to parse → call service → translate the
 * result into a status/body → broadcast. Broadcasts stay in the route (the service only returns the
 * visibility flags it needs) so "broadcast only after the transaction committed" stays structurally
 * guaranteed. Extracted from TodoRoutes in issue #546; behaviour is unchanged, guarded by the existing
 * TodoRouteTest round-trips.
 */
class TodoService(
    private val spawner: RecurrenceSpawner = RecurrenceSpawner(),
) {
    // ---- Lists -----------------------------------------------------------

    /** Shared lists are visible to everyone; private lists only to their creator. */
    suspend fun listLists(username: String): List<TodoListDto> = dbQuery {
        TodoListsTable.selectAll()
            .where { (TodoListsTable.visibility eq VISIBILITY_SHARED) or (TodoListsTable.createdBy eq username) }
            .orderBy(TodoListsTable.createdAt to SortOrder.ASC)
            .map { it.toListDto() }
    }

    sealed interface CreateListResult {
        data class Ok(val list: TodoListDto) : CreateListResult
        data class Invalid(val error: ErrorResponse) : CreateListResult
    }

    suspend fun createList(req: CreateTodoListRequest, username: String): CreateListResult {
        if (req.name.isBlank()) {
            return CreateListResult.Invalid(ErrorResponse("INVALID_LIST", "name must not be blank"))
        }
        val visibility = req.visibility ?: DEFAULT_LIST_VISIBILITY
        if (ListVisibility.parse(visibility) == null) {
            return CreateListResult.Invalid(ErrorResponse("INVALID_VISIBILITY", "visibility must be SHARED or PRIVATE"))
        }
        val list = dbQuery {
            val id = UUID.randomUUID()
            TodoListsTable.insert {
                it[TodoListsTable.id] = id
                it[name] = req.name.trim()
                it[TodoListsTable.visibility] = visibility
                it[createdBy] = username
                it[createdAt] = Instant.now()
            }
            TodoListsTable.selectAll().where { TodoListsTable.id eq id }.single().toListDto()
        }
        return CreateListResult.Ok(list)
    }

    /** A private->shared transition reveals the list's todos; [revealedTodos] carries them for replay. */
    sealed interface UpdateListResult {
        data class Ok(val wasShared: Boolean, val list: TodoListDto, val revealedTodos: List<TodoDto>) : UpdateListResult
        data class Invalid(val error: ErrorResponse) : UpdateListResult
        data object NotFound : UpdateListResult
    }

    suspend fun updateList(id: UUID, req: UpdateTodoListRequest, username: String): UpdateListResult {
        if (req.visibility != null && ListVisibility.parse(req.visibility) == null) {
            return UpdateListResult.Invalid(ErrorResponse("INVALID_VISIBILITY", "visibility must be SHARED or PRIVATE"))
        }
        if (req.name != null && req.name.isBlank()) {
            return UpdateListResult.Invalid(ErrorResponse("INVALID_LIST", "name must not be blank"))
        }
        return dbQuery {
            val existing = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
                ?: return@dbQuery UpdateListResult.NotFound
            // A private list belongs to its creator; nobody else may rename, re-share or even
            // observe it. Treat a foreign private list as non-existent so its UUID stays inert.
            if (existing[TodoListsTable.visibility] == VISIBILITY_PRIVATE && existing[TodoListsTable.createdBy] != username) {
                return@dbQuery UpdateListResult.NotFound
            }
            val wasShared = existing[TodoListsTable.visibility] == VISIBILITY_SHARED
            TodoListsTable.update({ TodoListsTable.id eq id }) {
                req.name?.let { v -> it[name] = v.trim() }
                req.visibility?.let { v -> it[visibility] = v }
            }
            val updated = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.single().toListDto()
            // private -> shared reveals the list's todos to the other client; they were never
            // broadcast while private, so load them here to replay over the channel (issue #75).
            val revealedTodos = if (!wasShared && updated.visibility != VISIBILITY_PRIVATE) {
                TodosTable.selectAll().where { TodosTable.listId eq id }
                    .orderBy(TodosTable.createdAt to SortOrder.ASC)
                    .map { it.toTodoDto() }
            } else emptyList()
            UpdateListResult.Ok(wasShared, updated, revealedTodos)
        }
    }

    sealed interface DeleteListResult {
        data class Ok(val list: TodoListDto) : DeleteListResult
        data object NotFound : DeleteListResult
    }

    suspend fun deleteList(id: UUID, username: String): DeleteListResult = dbQuery {
        val existing = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
            ?: return@dbQuery DeleteListResult.NotFound
        // Only the owner may delete a private list (see updateList above).
        if (existing[TodoListsTable.visibility] == VISIBILITY_PRIVATE && existing[TodoListsTable.createdBy] != username) {
            return@dbQuery DeleteListResult.NotFound
        }
        // delete the list's todos and their subtasks (mirrors ON DELETE CASCADE for the H2 test DB,
        // which models list_id without a FK; real Postgres cascades via V7)
        val todoIds = TodosTable.selectAll().where { TodosTable.listId eq id }
            .map { it[TodosTable.id] }
        if (todoIds.isNotEmpty()) {
            TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId inList todoIds }
            TodoAssigneesTable.deleteWhere { TodoAssigneesTable.todoId inList todoIds }
            TodosTable.deleteWhere { TodosTable.listId eq id }
        }
        TodoListsTable.deleteWhere { TodoListsTable.id eq id }
        DeleteListResult.Ok(existing.toListDto())
    }

    // ---- Todos -----------------------------------------------------------

    /**
     * Lists the todos visible to [username]. When [doneSince] is set, completed (DONE) todos whose
     * `done_at` falls before that day (server-zone local date) are dropped in SQL, so the endpoint no
     * longer ships the full DONE history the clients only window locally afterwards (#559). Open todos
     * (INBOX/PLANNED) are always returned regardless of age. [doneSince] == null keeps the historical
     * "return everything" behaviour, so a client that omits the param (or its "show all" mode) is
     * unaffected.
     */
    suspend fun listTodos(username: String, doneSince: LocalDate? = null): List<TodoDto> = dbQuery {
        // Hide todos that live in someone else's private list — filtered in SQL (#548) instead of
        // loading the whole table and dropping rows in Kotlin.
        val hiddenListIds = TodoListsTable.selectAll()
            .where { (TodoListsTable.visibility eq VISIBILITY_PRIVATE) and (TodoListsTable.createdBy neq username) }
            .map { it[TodoListsTable.id] }

        val query = TodosTable.selectAll()
        if (hiddenListIds.isNotEmpty()) {
            query.andWhere { TodosTable.listId.isNull() or (TodosTable.listId notInList hiddenListIds) }
        }
        if (doneSince != null) {
            // A DONE todo is kept only if it was completed on/after the cutoff day; everything not DONE
            // stays. The cutoff is the start of [doneSince] in the server zone — the same local-day basis
            // the clients (and the CSV export/forecast) use, so the window boundary lines up (#356/#559).
            //
            // A DONE row with a NULL done_at is kept too (#595): `done_at >= cutoff` is NULL for it, so
            // without the isNull() branch `(false OR NULL) = NULL` would silently drop such rows from
            // every windowed fetch. Normal writes always stamp done_at on the DONE transition, so this
            // only shields legacy/imported/hand-edited DONE rows from vanishing.
            val cutoff = doneSince.atStartOfDay(SERVER_ZONE).toInstant()
            query.andWhere {
                (TodosTable.status neq "DONE") or TodosTable.doneAt.isNull() or (TodosTable.doneAt greaterEq cutoff)
            }
        }
        val rows = query.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()

        // Batch-load subtasks and assignees for all visible todos in one query each (#548), turning the
        // former 1 + 2N queries into a constant 3. groupBy preserves the query's order within each group,
        // so the ORDER BY carries into the per-todo lists.
        val todoIds = rows.map { it[TodosTable.id] }
        val subtasksByTodo = TodoSubtasksTable.selectAll()
            .where { TodoSubtasksTable.todoId inList todoIds }
            .orderBy(TodoSubtasksTable.sortOrder to SortOrder.ASC)
            .groupBy({ it[TodoSubtasksTable.todoId] }, { it.toSubtaskDto() })
        val assigneesByTodo = TodoAssigneesTable.selectAll()
            .where { TodoAssigneesTable.todoId inList todoIds }
            .orderBy(TodoAssigneesTable.username to SortOrder.ASC)
            .groupBy({ it[TodoAssigneesTable.todoId] }, { it[TodoAssigneesTable.username] })

        rows.map { row ->
            val id = row[TodosTable.id]
            row.toTodoDto(subtasksByTodo[id] ?: emptyList(), assigneesByTodo[id] ?: emptyList())
        }
    }

    sealed interface CreateTodoResult {
        data class Ok(val mutation: TodoMutation) : CreateTodoResult
        data class Invalid(val error: ErrorResponse) : CreateTodoResult
        data object NotFound : CreateTodoResult
    }

    suspend fun createTodo(req: CreateTodoRequest, username: String): CreateTodoResult {
        // Capturing an assignee or due date plants the todo straight into PLANNED — the domain
        // rule is that PLANNED needs at least one of them. A bare title (or only a
        // description/priority, which alone can't satisfy PLANNED) stays in the INBOX. This lets
        // the quick-add "all-at-once" flow create a planned todo in a single POST instead of a
        // POST-then-PUT dance.
        val assignees = normalizeAssignees(req.assignees)
        val status = if (assignees.isNotEmpty() || !req.dueDate.isNullOrBlank()) TodoStatus.PLANNED.name else TodoStatus.INBOX.name
        validateTodoInput(
            title = req.title,
            status = status,
            assignees = assignees,
            dueDate = req.dueDate,
            dueTime = req.dueTime,
            reminderLeadMinutes = req.reminderLeadMinutes,
            priority = req.priority,
            recurrenceFreq = req.recurrence?.freq,
            recurrenceInterval = req.recurrence?.interval,
        )?.let { return CreateTodoResult.Invalid(it) }
        val listId = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (req.listId != null && req.listId.isNotBlank() && listId == null) {
            return CreateTodoResult.Invalid(ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
        }

        return dbQuery {
            // Every assignee must be a known household user (the join table FKs users.username).
            val unknownAssignees = unknownUsers(assignees)
            if (unknownAssignees.isNotEmpty()) {
                return@dbQuery CreateTodoResult.Invalid(
                    ErrorResponse("INVALID_ASSIGNEE", "unknown assignee(s): ${unknownAssignees.joinToString(", ")}"),
                )
            }
            // resolve the target list's visibility, enforcing ownership: a foreign private list is
            // treated as non-existent so it can neither be written into nor probed (#73)
            val listVisibility = if (listId != null) {
                writableListVisibility(listId, username) ?: return@dbQuery CreateTodoResult.NotFound
            } else null
            val id = UUID.randomUUID()
            val createdNow = Instant.now()
            TodosTable.insert {
                it[TodosTable.id] = id
                it[title] = req.title
                it[description] = req.description
                it[TodosTable.status] = status
                it[dueDate] = req.dueDate?.let { d -> LocalDate.parse(d) }
                it[dueTime] = req.dueTime?.takeIf { t -> t.isNotBlank() }?.let { t -> LocalTime.parse(t) }
                it[reminderLeadMinutes] = req.reminderLeadMinutes
                it[priority] = req.priority
                it[TodosTable.listId] = listId
                it[recurrence] = req.recurrence?.freq
                it[recurrenceInterval] = req.recurrence?.let { r -> r.interval.coerceAtLeast(1) }
                it[createdBy] = username
                it[createdAt] = createdNow
                it[updatedAt] = createdNow
            }
            setTodoAssignees(id, assignees)
            val dto = TodosTable.selectAll().where { TodosTable.id eq id }.single().toTodoDto()
            val shared = listVisibility != VISIBILITY_PRIVATE
            CreateTodoResult.Ok(TodoMutation(dto, wasShared = shared, isShared = shared))
        }
    }

    sealed interface UpdateTodoResult {
        data class Ok(val mutation: TodoMutation) : UpdateTodoResult
        data class Invalid(val error: ErrorResponse) : UpdateTodoResult
        // Carries the 404 message: a missing/invisible todo answers "Todo not found", while moving
        // into an unknown or foreign-private target list answers "List not found" (both 404, no
        // existence oracle, #73) — preserving the exact bodies the pre-service route returned.
        data class NotFound(val message: String) : UpdateTodoResult
    }

    suspend fun updateTodo(id: UUID, req: UpdateTodoRequest, username: String): UpdateTodoResult {
        // null = unchanged, "" = clear, else target list id (must exist)
        val targetListId: UUID? = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (req.listId != null && req.listId.isNotBlank() && targetListId == null) {
            return UpdateTodoResult.Invalid(ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
        }

        return dbQuery {
            val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                ?: return@dbQuery UpdateTodoResult.NotFound("Todo not found")
            // a todo in someone else's private list is invisible to the caller (see GET filter);
            // treat it as non-existent so its UUID can't be written through or probed here (#73)
            if (!listVisibleTo(existing[TodosTable.listId], username)) return@dbQuery UpdateTodoResult.NotFound("Todo not found")
            // capture the pre-update visibility so the broadcast can translate transitions
            val wasShared = listIsShared(existing[TodosTable.listId])
            val nextStatus = req.status ?: existing[TodosTable.status]
            // Optional text fields follow the #265 tri-state String convention (null=keep, ""=clear,
            // else set), now expressed by Patch<T>/asPatch() instead of a hand-rolled if per field.
            val nextDescription = req.description.asPatch().resolve(existing[TodosTable.description])
            // assignees (V39): null = unchanged (keep the current set), [] = clear, non-empty = replace
            val nextAssignees = if (req.assignees != null) normalizeAssignees(req.assignees) else loadTodoAssignees(id)
            val nextDueDate = req.dueDate.asPatch().resolve(existing[TodosTable.dueDate]?.toString())
            // due_time follows the #265 string convention; reminder uses negative = clear. Both
            // are meaningless without a date, so clearing the date cascades them to null (also
            // keeps the DB CHECKs satisfied).
            val rawNextDueTime = req.dueTime.asPatch().resolve(existing[TodosTable.dueTime]?.toString())
            val rawNextReminder = if (req.reminderLeadMinutes != null) req.reminderLeadMinutes.takeIf { it >= 0 } else existing[TodosTable.reminderLeadMinutes]
            val nextDueTime = if (nextDueDate == null) null else rawNextDueTime
            val nextReminderLead = if (nextDueDate == null) null else rawNextReminder
            val nextPriority = req.priority.asPatch().resolve(existing[TodosTable.priority])
            // merge the recurrence rule: absent = unchanged, freq "NONE" = clear, else set/replace
            val (nextRecFreq, nextRecInterval) = when {
                req.recurrence == null -> existing[TodosTable.recurrence] to existing[TodosTable.recurrenceInterval]
                req.recurrence.freq == Recurrence.CLEAR -> null to null
                else -> req.recurrence.freq to req.recurrence.interval.coerceAtLeast(1)
            }
            // an assignee touched by this request must be a known household user (join-table FK)
            if (req.assignees != null) {
                val unknown = unknownUsers(nextAssignees)
                if (unknown.isNotEmpty()) {
                    return@dbQuery UpdateTodoResult.Invalid(
                        ErrorResponse("INVALID_ASSIGNEE", "unknown assignee(s): ${unknown.joinToString(", ")}"),
                    )
                }
            }
            validateTodoInput(
                title = req.title ?: existing[TodosTable.title],
                status = nextStatus,
                assignees = nextAssignees,
                dueDate = nextDueDate,
                dueTime = nextDueTime,
                reminderLeadMinutes = nextReminderLead,
                priority = nextPriority,
                recurrenceFreq = nextRecFreq,
                recurrenceInterval = nextRecInterval,
            )?.let { return@dbQuery UpdateTodoResult.Invalid(it) }
            // moving into a list requires it to be writable: unknown or foreign-private -> 404 (#73)
            if (targetListId != null && writableListVisibility(targetListId, username) == null) {
                return@dbQuery UpdateTodoResult.NotFound("List not found")
            }
            // null = unchanged keeps the old list; "" cleared it (targetListId == null)
            val newListId = if (req.listId != null) targetListId else existing[TodosTable.listId]
            // a recurring todo spawns its successor the moment it first transitions into DONE
            val becomingDone = req.status == TodoStatus.DONE.name && existing[TodosTable.status] != TodoStatus.DONE.name
            val spawnNext = becomingDone && nextRecFreq != null

            TodosTable.update({ TodosTable.id eq id }) {
                req.title?.let { v -> it[title] = v }
                // null = unchanged, "" = clear to null, else set (mirrors assignee/listId, #265)
                req.description?.let { _ -> it[description] = nextDescription }
                req.dueDate?.let { _ -> it[dueDate] = nextDueDate?.let { d -> LocalDate.parse(d) } }
                // Re-evaluate due_time/reminder when the request touched the date (cascade clear)
                // or the field itself; leave untouched otherwise.
                if (req.dueDate != null || req.dueTime != null) {
                    it[dueTime] = nextDueTime?.let { t -> LocalTime.parse(t) }
                }
                if (req.dueDate != null || req.reminderLeadMinutes != null) {
                    it[reminderLeadMinutes] = nextReminderLead
                }
                // Re-arm the reminder when the due moment actually changes, so a rescheduled todo
                // gets a fresh reminder rather than staying retired (#429 Phase 2a). The time is
                // compared as a parsed LocalTime (not its string form) so "14:30" vs "14:30:00"
                // can't trip a spurious re-arm; an untouched save is a no-op.
                val oldTime = existing[TodosTable.dueTime]
                val newTime = nextDueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val timeChanged =
                    if (oldTime == null || newTime == null) (oldTime == null) != (newTime == null)
                    else oldTime.compareTo(newTime) != 0
                val dueMomentChanged =
                    existing[TodosTable.dueDate]?.toString() != nextDueDate ||
                        timeChanged ||
                        existing[TodosTable.reminderLeadMinutes] != nextReminderLead
                if (dueMomentChanged) it[reminderSentAt] = null
                req.priority?.let { _ -> it[priority] = nextPriority }
                req.listId?.let { _ -> it[listId] = targetListId }
                req.recurrence?.let { r ->
                    if (r.freq == Recurrence.CLEAR) { it[recurrence] = null; it[recurrenceInterval] = null }
                    else { it[recurrence] = r.freq; it[recurrenceInterval] = r.interval.coerceAtLeast(1) }
                }
                req.status?.let { v ->
                    it[status] = v
                    it[doneAt] = if (v == "DONE") Instant.now() else null
                }
                // the rule moves to the freshly spawned successor; the completed instance becomes
                // plain history so the safety-net scheduler never re-spawns from it
                if (spawnNext) { it[recurrence] = null; it[recurrenceInterval] = null }
                // any PUT that reaches here is a real edit → bump the last-modified stamp
                it[updatedAt] = Instant.now()
            }
            // Only rewrite the assignee set when the request carried the field (null = unchanged).
            if (req.assignees != null) setTodoAssignees(id, nextAssignees)

            var spawned: TodoDto? = null
            var spawnedShared = false
            if (spawnNext) {
                // a recurring todo always has a dueDate anchor (validation enforces it), so
                // nextDueDate is non-null here; parse the merged value rather than the raw request
                val newId = spawner.spawn(
                    RecurrenceSpawner.Spec(
                        sourceTodoId = id,
                        title = req.title ?: existing[TodosTable.title],
                        description = nextDescription,
                        dueTime = nextDueTime,
                        reminderLeadMinutes = nextReminderLead,
                        priority = nextPriority,
                        listId = newListId,
                        freq = nextRecFreq!!,
                        interval = nextRecInterval ?: 1,
                        anchorDueDate = LocalDate.parse(nextDueDate!!),
                        createdBy = existing[TodosTable.createdBy],
                        assignees = nextAssignees,
                    ),
                )
                spawned = TodosTable.selectAll().where { TodosTable.id eq newId }.single().toTodoDto()
                spawnedShared = listIsShared(newListId)
            }

            val dto = TodosTable.selectAll().where { TodosTable.id eq id }.single().toTodoDto()
            UpdateTodoResult.Ok(
                TodoMutation(dto, wasShared = wasShared, isShared = listIsShared(newListId), spawned = spawned, spawnedShared = spawnedShared),
            )
        }
    }

    sealed interface DeleteTodoResult {
        data class Ok(val todo: TodoDto, val shared: Boolean) : DeleteTodoResult
        data object NotFound : DeleteTodoResult
    }

    suspend fun deleteTodo(id: UUID, username: String): DeleteTodoResult = dbQuery {
        val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
            ?: return@dbQuery DeleteTodoResult.NotFound
        // a todo in someone else's private list is invisible; treat it as non-existent (#73)
        if (!listVisibleTo(existing[TodosTable.listId], username)) return@dbQuery DeleteTodoResult.NotFound
        val shared = listIsShared(existing[TodosTable.listId])
        // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
        TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId eq id }
        TodoAssigneesTable.deleteWhere { TodoAssigneesTable.todoId eq id }
        TodosTable.deleteWhere { TodosTable.id eq id }
        DeleteTodoResult.Ok(existing.toTodoDto(), shared)
    }

    // ---- Subtasks --------------------------------------------------------

    sealed interface SubtaskResult {
        data class Ok(val mutation: TodoMutation) : SubtaskResult
        data class Invalid(val error: ErrorResponse) : SubtaskResult
        data object NotFound : SubtaskResult
    }

    suspend fun addSubtask(todoId: UUID, req: CreateSubtaskRequest, username: String): SubtaskResult {
        if (req.title.isBlank()) {
            return SubtaskResult.Invalid(ErrorResponse("INVALID_SUBTASK", "title must not be blank"))
        }
        return dbQuery {
            if (!parentTodoVisibleTo(todoId, username)) return@dbQuery SubtaskResult.NotFound
            val nextOrder = (TodoSubtasksTable.selectAll()
                .where { TodoSubtasksTable.todoId eq todoId }
                .maxOfOrNull { it[TodoSubtasksTable.sortOrder] } ?: -1) + 1
            TodoSubtasksTable.insert {
                it[id] = UUID.randomUUID()
                it[TodoSubtasksTable.todoId] = todoId
                it[title] = req.title.trim()
                it[done] = false
                it[sortOrder] = nextOrder
                it[createdAt] = Instant.now()
            }
            SubtaskResult.Ok(todoWithVisibility(todoId))
        }
    }

    suspend fun updateSubtask(todoId: UUID, subtaskId: UUID, req: UpdateSubtaskRequest, username: String): SubtaskResult {
        if (req.title != null && req.title.isBlank()) {
            return SubtaskResult.Invalid(ErrorResponse("INVALID_SUBTASK", "title must not be blank"))
        }
        return dbQuery {
            // a subtask under a foreign private todo is as hidden as a missing one -> same 404
            if (!parentTodoVisibleTo(todoId, username)) return@dbQuery SubtaskResult.NotFound
            val exists = TodoSubtasksTable.selectAll()
                .where { (TodoSubtasksTable.id eq subtaskId) and (TodoSubtasksTable.todoId eq todoId) }
                .empty().not()
            if (!exists) return@dbQuery SubtaskResult.NotFound
            TodoSubtasksTable.update({ TodoSubtasksTable.id eq subtaskId }) {
                req.title?.let { v -> it[title] = v.trim() }
                req.done?.let { v -> it[done] = v }
            }
            SubtaskResult.Ok(todoWithVisibility(todoId))
        }
    }

    suspend fun deleteSubtask(todoId: UUID, subtaskId: UUID, username: String): SubtaskResult = dbQuery {
        // a subtask under a foreign private todo is as hidden as a missing one -> same 404
        if (!parentTodoVisibleTo(todoId, username)) return@dbQuery SubtaskResult.NotFound
        val deleted = TodoSubtasksTable.deleteWhere {
            (TodoSubtasksTable.id eq subtaskId) and (TodoSubtasksTable.todoId eq todoId)
        }
        if (deleted == 0) return@dbQuery SubtaskResult.NotFound
        SubtaskResult.Ok(todoWithVisibility(todoId))
    }
}

// ---- Visibility helpers (#73) --------------------------------------------
// Kept as internal top-level functions so the recurrence safety-net service can reuse them and the
// route's post-commit broadcasts can query the same visibility rules. Each must run in a transaction.

/**
 * The "todos" WS channel reaches both users, so a todo in someone else's private list must never be
 * pushed over it. A todo's visibility is its list's: a todo with no list or in a SHARED list is
 * visible to both; a todo in a PRIVATE list only to that list's owner.
 */
internal fun listIsShared(listId: UUID?): Boolean {
    if (listId == null) return true
    return TodoListsTable.selectAll().where { TodoListsTable.id eq listId }
        .singleOrNull()?.get(TodoListsTable.visibility) != VISIBILITY_PRIVATE
}

/**
 * Resolves the visibility of a list a caller wants to write a todo *into*, enforcing private-list
 * ownership. Returns the visibility for a writable list (SHARED, or a PRIVATE list owned by
 * [username]); returns null when the list does not exist OR is someone else's PRIVATE list. Callers
 * must answer both null cases with the same 404 "List not found" so a foreign private list's UUID
 * stays indistinguishable from an unknown one — no cross-tenant write, no existence oracle (#73).
 */
private fun writableListVisibility(listId: UUID, username: String): String? {
    val row = TodoListsTable.selectAll().where { TodoListsTable.id eq listId }.singleOrNull() ?: return null
    val visibility = row[TodoListsTable.visibility]
    if (visibility == VISIBILITY_PRIVATE && row[TodoListsTable.createdBy] != username) return null
    return visibility
}

/**
 * Whether the todo living in [listId]'s list is visible to [username]. A todo in someone else's
 * PRIVATE list is hidden (it is already filtered out of GET /todos), so write paths must treat it as
 * non-existent — the same 404 they return for an unknown id, leaking neither its contents nor its
 * existence (#73). A null/unknown list (orphaned list_id, only possible on the FK-less H2 test DB)
 * counts as visible, mirroring [listIsShared].
 */
private fun listVisibleTo(listId: UUID?, username: String): Boolean {
    if (listId == null) return true
    val row = TodoListsTable.selectAll().where { TodoListsTable.id eq listId }.singleOrNull() ?: return true
    return row[TodoListsTable.visibility] != VISIBILITY_PRIVATE || row[TodoListsTable.createdBy] == username
}

/**
 * Whether the subtask endpoints may act on [todoId] for [username]: the parent todo must exist and
 * must not live in someone else's private list. False covers both an unknown id and a hidden one, so
 * callers return the same 404 for each — no subtask write into a foreign private list, no oracle (#73).
 */
private fun parentTodoVisibleTo(todoId: UUID, username: String): Boolean {
    val row = TodosTable.selectAll().where { TodosTable.id eq todoId }.singleOrNull() ?: return false
    return listVisibleTo(row[TodosTable.listId], username)
}

/** Loads the parent todo after a subtask change together with its list visibility. */
private fun todoWithVisibility(todoId: UUID): TodoMutation {
    val row = TodosTable.selectAll().where { TodosTable.id eq todoId }.single()
    val shared = listIsShared(row[TodosTable.listId])
    // a subtask edit never moves the todo between lists, so visibility is unchanged
    return TodoMutation(row.toTodoDto(), wasShared = shared, isShared = shared)
}

// ---- Validation & assignees ----------------------------------------------

private fun validateTodoInput(
    title: String,
    status: String,
    assignees: List<String>,
    dueDate: String?,
    priority: String?,
    dueTime: String? = null,
    reminderLeadMinutes: Int? = null,
    recurrenceFreq: String? = null,
    recurrenceInterval: Int? = null,
): ErrorResponse? {
    if (title.isBlank()) return ErrorResponse("INVALID_TODO", "title must not be blank")
    if (TodoStatus.parse(status) == null) {
        return ErrorResponse("INVALID_STATUS", "status must be INBOX, PLANNED or DONE")
    }
    if (priority != null && TodoPriority.parse(priority) == null) {
        return ErrorResponse("INVALID_PRIORITY", "priority must be LOW, MEDIUM or HIGH")
    }
    if (dueDate != null) {
        runCatching { LocalDate.parse(dueDate) }.getOrElse {
            return ErrorResponse("INVALID_DUE_DATE", "dueDate must be in YYYY-MM-DD format")
        }
    }
    // A time-of-day / reminder is an extra on the due date — both require one and are bounded.
    if (!dueTime.isNullOrBlank()) {
        runCatching { LocalTime.parse(dueTime) }.getOrElse {
            return ErrorResponse("INVALID_DUE_TIME", "dueTime must be in HH:mm format")
        }
        if (dueDate.isNullOrBlank()) {
            return ErrorResponse("INVALID_DUE_TIME", "dueTime requires a dueDate")
        }
    }
    if (reminderLeadMinutes != null) {
        if (reminderLeadMinutes < 0) {
            return ErrorResponse("INVALID_REMINDER", "reminderLeadMinutes must be >= 0")
        }
        if (dueDate.isNullOrBlank()) {
            return ErrorResponse("INVALID_REMINDER", "reminderLeadMinutes requires a dueDate")
        }
    }
    // recurrenceFreq/Interval are the *merged* (post-update) values; null means "no recurrence".
    // Validated BEFORE the PLANNED rule below: a recurring todo missing its dueDate anchor should
    // report the specific INVALID_RECURRENCE (its root cause), not the generic "needs assignee or
    // dueDate". Both would reject, but the recurrence message is the precise one — and this ordering
    // matters now that an assignee/dueDate on create makes a todo PLANNED (so a recurring todo is
    // born PLANNED and would otherwise trip the PLANNED check first when its anchor is cleared).
    if (recurrenceFreq != null) {
        if (RecurrenceFreq.parse(recurrenceFreq) == null) {
            return ErrorResponse("INVALID_RECURRENCE", "recurrence.freq must be DAILY, WEEKLY or MONTHLY")
        }
        if (recurrenceInterval != null && recurrenceInterval !in 1..Recurrence.MAX_INTERVAL) {
            return ErrorResponse("INVALID_RECURRENCE", "recurrence.interval must be between 1 and ${Recurrence.MAX_INTERVAL}")
        }
        if (dueDate.isNullOrBlank()) {
            return ErrorResponse("INVALID_RECURRENCE", "a recurring todo needs a dueDate as its schedule anchor")
        }
    }
    if (status == TodoStatus.PLANNED.name && assignees.isEmpty() && dueDate.isNullOrBlank()) {
        return ErrorResponse("INVALID_TODO", "PLANNED todos need an assignee or dueDate")
    }
    return null
}

/** Trim, drop blanks and de-duplicate an incoming assignee list (order-preserving). */
private fun normalizeAssignees(raw: List<String>?): List<String> =
    raw?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

/** Assignees, sorted for a stable payload. Must run inside a transaction. */
internal fun loadTodoAssignees(todoId: UUID): List<String> =
    TodoAssigneesTable.selectAll().where { TodoAssigneesTable.todoId eq todoId }
        .orderBy(TodoAssigneesTable.username to SortOrder.ASC)
        .map { it[TodoAssigneesTable.username] }

/** Replaces a todo's assignee set with [usernames] (assumed already validated). In a transaction. */
private fun setTodoAssignees(todoId: UUID, usernames: List<String>) {
    TodoAssigneesTable.deleteWhere { TodoAssigneesTable.todoId eq todoId }
    usernames.forEach { u ->
        TodoAssigneesTable.insert {
            it[TodoAssigneesTable.todoId] = todoId
            it[username] = u
        }
    }
}

/** The subset of [usernames] that are not known household users. Empty input → empty. In a transaction. */
private fun unknownUsers(usernames: List<String>): List<String> {
    if (usernames.isEmpty()) return emptyList()
    val known = UsersTable.selectAll().where { UsersTable.username inList usernames }
        .map { it[UsersTable.username] }.toSet()
    return usernames.filter { it !in known }
}

// ---- Mappers -------------------------------------------------------------

private fun ResultRow.toSubtaskDto() = SubtaskDto(
    id = this[TodoSubtasksTable.id].toString(),
    title = this[TodoSubtasksTable.title],
    done = this[TodoSubtasksTable.done],
    sortOrder = this[TodoSubtasksTable.sortOrder],
)

private fun ResultRow.toListDto() = TodoListDto(
    id = this[TodoListsTable.id].toString(),
    name = this[TodoListsTable.name],
    visibility = this[TodoListsTable.visibility],
    createdBy = this[TodoListsTable.createdBy],
    createdAt = this[TodoListsTable.createdAt].toString(),
)

/**
 * Single-todo mapper: loads this todo's subtasks + assignees itself (two extra queries). Fine for the
 * write paths that only touch one todo; the list path uses the batch overload below to avoid N+1 (#548).
 */
internal fun ResultRow.toTodoDto(): TodoDto {
    val todoId = this[TodosTable.id]
    val subtasks = TodoSubtasksTable.selectAll()
        .where { TodoSubtasksTable.todoId eq todoId }
        .orderBy(TodoSubtasksTable.sortOrder to SortOrder.ASC)
        .map { it.toSubtaskDto() }
    return toTodoDto(subtasks, loadTodoAssignees(todoId))
}

/**
 * Pure mapper taking pre-fetched [subtasks] and [assignees] — no DB access. Lets the list path load both
 * relations in one batched query each and map without per-row queries (#548).
 */
internal fun ResultRow.toTodoDto(subtasks: List<SubtaskDto>, assignees: List<String>): TodoDto {
    val todoId = this[TodosTable.id]
    return TodoDto(
        id = todoId.toString(),
        title = this[TodosTable.title],
        description = this[TodosTable.description],
        status = this[TodosTable.status],
        assignees = assignees,
        dueDate = this[TodosTable.dueDate]?.toString(),
        // LocalTime.toString() is "HH:mm" (or "HH:mm:ss" with seconds) — clients tolerate both.
        dueTime = this[TodosTable.dueTime]?.toString(),
        reminderLeadMinutes = this[TodosTable.reminderLeadMinutes],
        priority = this[TodosTable.priority],
        listId = this[TodosTable.listId]?.toString(),
        recurrence = this[TodosTable.recurrence]?.let { RecurrenceDto(it, this[TodosTable.recurrenceInterval] ?: 1) },
        subtasks = subtasks,
        createdBy = this[TodosTable.createdBy],
        createdAt = this[TodosTable.createdAt].toString(),
        updatedAt = this[TodosTable.updatedAt].toString(),
        doneAt = this[TodosTable.doneAt]?.toString(),
    )
}
