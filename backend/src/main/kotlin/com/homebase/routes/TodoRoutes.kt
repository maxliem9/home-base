package com.homebase.routes

import com.homebase.db.TodoAssigneesTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UsersTable
import com.homebase.model.*
import com.homebase.recurrence.Recurrence
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

private const val TODO_WS_CHANNEL = "todos"
private const val VISIBILITY_SHARED = "SHARED"
private const val VISIBILITY_PRIVATE = "PRIVATE"
private const val DEFAULT_LIST_VISIBILITY = VISIBILITY_SHARED
private val VALID_TODO_STATUSES = setOf("INBOX", "PLANNED", "DONE")
private val VALID_TODO_PRIORITIES = setOf("LOW", "MEDIUM", "HIGH")
private val VALID_LIST_VISIBILITIES = setOf(VISIBILITY_SHARED, VISIBILITY_PRIVATE)

fun Route.todoRoutes() {
    route("/todos") {
        // ---- Lists (registered before /{id} so the static segment wins) ----
        route("/lists") {
            get {
                val username = call.username()
                val lists = transaction {
                    // shared lists are visible to everyone; private lists only to their creator
                    TodoListsTable.selectAll()
                        .where { (TodoListsTable.visibility eq VISIBILITY_SHARED) or (TodoListsTable.createdBy eq username) }
                        .orderBy(TodoListsTable.createdAt to SortOrder.ASC)
                        .map { it.toListDto() }
                }
                call.respond(lists)
            }

            post {
                val username = call.username()
                val req = call.receive<CreateTodoListRequest>()
                if (req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_LIST", "name must not be blank"))
                    return@post
                }
                val visibility = req.visibility ?: DEFAULT_LIST_VISIBILITY
                if (visibility !in VALID_LIST_VISIBILITIES) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_VISIBILITY", "visibility must be SHARED or PRIVATE"))
                    return@post
                }
                val list = transaction {
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
                broadcastListCreate(list)
                call.respond(HttpStatusCode.Created, list)
            }

            put("/{id}") {
                val username = call.username()
                val id = call.uuidParam() ?: return@put
                val req = call.receive<UpdateTodoListRequest>()
                if (req.visibility != null && req.visibility !in VALID_LIST_VISIBILITIES) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_VISIBILITY", "visibility must be SHARED or PRIVATE"))
                    return@put
                }
                if (req.name != null && req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_LIST", "name must not be blank"))
                    return@put
                }
                val result = transaction {
                    val existing = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
                    // A private list belongs to its creator; nobody else may rename, re-share or even
                    // observe it. Treat a foreign private list as non-existent so its UUID stays inert.
                    if (existing[TodoListsTable.visibility] == VISIBILITY_PRIVATE && existing[TodoListsTable.createdBy] != username) {
                        return@transaction null
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
                    Triple(wasShared, updated, revealedTodos)
                }
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@put
                }
                val (wasShared, list, revealedTodos) = result
                broadcastListUpdate(wasShared, list, revealedTodos)
                call.respond(list)
            }

            delete("/{id}") {
                val username = call.username()
                val id = call.uuidParam() ?: return@delete
                val deleted = transaction {
                    val existing = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
                    // Only the owner may delete a private list (see PUT above).
                    if (existing[TodoListsTable.visibility] == VISIBILITY_PRIVATE && existing[TodoListsTable.createdBy] != username) {
                        return@transaction null
                    }
                    // delete the list's todos and their subtasks (mirrors ON DELETE CASCADE for
                    // the H2 test DB, which models list_id without a FK; real Postgres cascades via V7)
                    val todoIds = TodosTable.selectAll().where { TodosTable.listId eq id }
                        .map { it[TodosTable.id] }
                    if (todoIds.isNotEmpty()) {
                        TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId inList todoIds }
                        TodoAssigneesTable.deleteWhere { TodoAssigneesTable.todoId inList todoIds }
                        TodosTable.deleteWhere { TodosTable.listId eq id }
                    }
                    TodoListsTable.deleteWhere { TodoListsTable.id eq id }
                    existing.toListDto()
                }
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@delete
                }
                broadcastListDelete(deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get {
            val username = call.username()
            val todos = transaction {
                // hide todos that live in someone else's private list
                val hiddenListIds = TodoListsTable.selectAll()
                    .where { (TodoListsTable.visibility eq VISIBILITY_PRIVATE) and (TodoListsTable.createdBy neq username) }
                    .map { it[TodoListsTable.id] }
                    .toSet()
                TodosTable.selectAll()
                    .map { it.toTodoDto() }
                    .filter { it.listId == null || UUID.fromString(it.listId) !in hiddenListIds }
            }
            call.respond(todos)
        }

        post {
            val username = call.username()
            val req = call.receive<CreateTodoRequest>()
            // Capturing an assignee or due date plants the todo straight into PLANNED — the domain
            // rule is that PLANNED needs at least one of them. A bare title (or only a
            // description/priority, which alone can't satisfy PLANNED) stays in the INBOX. This lets
            // the quick-add "all-at-once" flow create a planned todo in a single POST instead of a
            // POST-then-PUT dance.
            val assignees = normalizeAssignees(req.assignees)
            val status = if (assignees.isNotEmpty() || !req.dueDate.isNullOrBlank()) "PLANNED" else "INBOX"
            val validationError = validateTodoInput(
                title = req.title,
                status = status,
                assignees = assignees,
                dueDate = req.dueDate,
                dueTime = req.dueTime,
                reminderLeadMinutes = req.reminderLeadMinutes,
                priority = req.priority,
                recurrenceFreq = req.recurrence?.freq,
                recurrenceInterval = req.recurrence?.interval,
            )
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, validationError)
                return@post
            }
            val listId = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.listId != null && req.listId.isNotBlank() && listId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
                return@post
            }
            // Every assignee must be a known household user (the join table FKs users.username).
            val unknownAssignees = transaction { unknownUsers(assignees) }
            if (unknownAssignees.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ASSIGNEE", "unknown assignee(s): ${unknownAssignees.joinToString(", ")}"))
                return@post
            }

            val result = transaction {
                // resolve the target list's visibility, enforcing ownership: a foreign private list
                // is treated as non-existent so it can neither be written into nor probed (#73)
                val listVisibility = if (listId != null) {
                    writableListVisibility(listId, username)
                        ?: return@transaction ErrorResponse("NOT_FOUND", "List not found")
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
                TodoMutation(dto, wasShared = shared, isShared = shared)
            }

            if (result is ErrorResponse) {
                // the only in-transaction error is the unknown/foreign list -> 404 (no existence oracle)
                call.respond(HttpStatusCode.NotFound, result)
                return@post
            }
            result as TodoMutation
            broadcastTodoCreate(result.isShared, result.todo)
            call.respond(HttpStatusCode.Created, result.todo)
        }

        put("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateTodoRequest>()
            // null = unchanged, "" = clear, else target list id (must exist)
            val targetListId: UUID? = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.listId != null && req.listId.isNotBlank() && targetListId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
                return@put
            }

            val result = transaction {
                val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                // a todo in someone else's private list is invisible to the caller (see GET filter);
                // treat it as non-existent so its UUID can't be written through or probed here (#73)
                if (!listVisibleTo(existing[TodosTable.listId], username)) return@transaction null
                // capture the pre-update visibility so the broadcast can translate transitions
                val wasShared = listIsShared(existing[TodosTable.listId])
                val nextStatus = req.status ?: existing[TodosTable.status]
                // optional text fields follow the listId convention (#265): null = unchanged,
                // "" = clear to null, else set. `if present, blank→null` captures all three.
                val nextDescription = if (req.description != null) req.description.ifBlank { null } else existing[TodosTable.description]
                // assignees (V39): null = unchanged (keep the current set), [] = clear, non-empty = replace
                val nextAssignees = if (req.assignees != null) normalizeAssignees(req.assignees) else loadTodoAssignees(id)
                val nextDueDate = if (req.dueDate != null) req.dueDate.ifBlank { null } else existing[TodosTable.dueDate]?.toString()
                // due_time follows the #265 string convention; reminder uses negative = clear. Both
                // are meaningless without a date, so clearing the date cascades them to null (also
                // keeps the DB CHECKs satisfied).
                val rawNextDueTime = if (req.dueTime != null) req.dueTime.ifBlank { null } else existing[TodosTable.dueTime]?.toString()
                val rawNextReminder = if (req.reminderLeadMinutes != null) req.reminderLeadMinutes.takeIf { it >= 0 } else existing[TodosTable.reminderLeadMinutes]
                val nextDueTime = if (nextDueDate == null) null else rawNextDueTime
                val nextReminderLead = if (nextDueDate == null) null else rawNextReminder
                val nextPriority = if (req.priority != null) req.priority.ifBlank { null } else existing[TodosTable.priority]
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
                        return@transaction ErrorResponse("INVALID_ASSIGNEE", "unknown assignee(s): ${unknown.joinToString(", ")}")
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
                )?.let { return@transaction it }
                // moving into a list requires it to be writable: unknown or foreign-private -> 404 (#73)
                if (targetListId != null && writableListVisibility(targetListId, username) == null) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }
                // null = unchanged keeps the old list; "" cleared it (targetListId == null)
                val newListId = if (req.listId != null) targetListId else existing[TodosTable.listId]
                // a recurring todo spawns its successor the moment it first transitions into DONE
                val becomingDone = req.status == "DONE" && existing[TodosTable.status] != "DONE"
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
                    val anchor = LocalDate.parse(nextDueDate!!)
                    val successorDue = Recurrence.nextDueAfterCompletion(
                        anchor, nextRecFreq!!, nextRecInterval ?: 1, LocalDate.now(),
                    )
                    val newId = UUID.randomUUID()
                    val now = Instant.now()
                    TodosTable.insert {
                        it[TodosTable.id] = newId
                        it[title] = req.title ?: existing[TodosTable.title]
                        it[description] = nextDescription
                        it[status] = "PLANNED" // always has a dueDate, so PLANNED is valid
                        it[dueDate] = successorDue
                        // carry the due time + reminder onto the successor (it keeps its dueDate anchor)
                        it[dueTime] = nextDueTime?.let { t -> LocalTime.parse(t) }
                        it[reminderLeadMinutes] = nextReminderLead
                        it[priority] = nextPriority
                        it[listId] = newListId
                        it[recurrence] = nextRecFreq
                        it[recurrenceInterval] = nextRecInterval ?: 1
                        it[createdBy] = existing[TodosTable.createdBy]
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                    // carry the subtasks over as a fresh, unchecked checklist for the new instance
                    TodoSubtasksTable.selectAll().where { TodoSubtasksTable.todoId eq id }
                        .orderBy(TodoSubtasksTable.sortOrder to SortOrder.ASC)
                        .forEach { sub ->
                            TodoSubtasksTable.insert {
                                it[TodoSubtasksTable.id] = UUID.randomUUID()
                                it[todoId] = newId
                                it[title] = sub[TodoSubtasksTable.title]
                                it[done] = false
                                it[sortOrder] = sub[TodoSubtasksTable.sortOrder]
                                it[createdAt] = now
                            }
                        }
                    // the successor inherits the assignee set (recurrence rule moves onto it)
                    setTodoAssignees(newId, nextAssignees)
                    spawned = TodosTable.selectAll().where { TodosTable.id eq newId }.single().toTodoDto()
                    spawnedShared = listIsShared(newListId)
                }

                val dto = TodosTable.selectAll().where { TodosTable.id eq id }.single().toTodoDto()
                TodoMutation(dto, wasShared = wasShared, isShared = listIsShared(newListId), spawned = spawned, spawnedShared = spawnedShared)
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@put
            }
            if (result is ErrorResponse) {
                // validation failures are 400; the unknown/foreign target list is 404 (no oracle)
                val status = if (result.code == "NOT_FOUND") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                call.respond(status, result)
                return@put
            }

            result as TodoMutation
            broadcastTodoUpdate(result.wasShared, result.isShared, result.todo)
            // the recurrence successor (if any) reaches the other client as a fresh create
            result.spawned?.let { broadcastTodoCreate(result.spawnedShared, it) }
            call.respond(result.todo)
        }

        delete("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@delete
            val result = transaction {
                val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                // a todo in someone else's private list is invisible; treat it as non-existent (#73)
                if (!listVisibleTo(existing[TodosTable.listId], username)) return@transaction null
                val shared = listIsShared(existing[TodosTable.listId])
                // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
                TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId eq id }
                TodoAssigneesTable.deleteWhere { TodoAssigneesTable.todoId eq id }
                TodosTable.deleteWhere { TodosTable.id eq id }
                existing.toTodoDto() to shared
            }
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@delete
            }
            val (deletedTodo, shared) = result
            broadcastTodoDelete(shared, deletedTodo)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Subtasks ----
        route("/{id}/subtasks") {
            post {
                val username = call.username()
                val todoId = call.uuidParam() ?: return@post
                val req = call.receive<CreateSubtaskRequest>()
                if (req.title.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SUBTASK", "title must not be blank"))
                    return@post
                }
                val result = transaction {
                    if (!parentTodoVisibleTo(todoId, username)) return@transaction null
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
                    todoWithVisibility(todoId)
                }
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                    return@post
                }
                broadcastTodoSubtaskChange(result)
                call.respond(HttpStatusCode.Created, result.todo)
            }

            put("/{subtaskId}") {
                val username = call.username()
                val todoId = call.uuidParam() ?: return@put
                val subtaskId = call.uuidParam("subtaskId") ?: return@put
                val req = call.receive<UpdateSubtaskRequest>()
                if (req.title != null && req.title.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SUBTASK", "title must not be blank"))
                    return@put
                }
                val result = transaction {
                    // a subtask under a foreign private todo is as hidden as a missing one -> same 404
                    if (!parentTodoVisibleTo(todoId, username)) return@transaction null
                    val exists = TodoSubtasksTable.selectAll()
                        .where { (TodoSubtasksTable.id eq subtaskId) and (TodoSubtasksTable.todoId eq todoId) }
                        .empty().not()
                    if (!exists) return@transaction null
                    TodoSubtasksTable.update({ TodoSubtasksTable.id eq subtaskId }) {
                        req.title?.let { v -> it[title] = v.trim() }
                        req.done?.let { v -> it[done] = v }
                    }
                    todoWithVisibility(todoId)
                }
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Subtask not found"))
                    return@put
                }
                broadcastTodoSubtaskChange(result)
                call.respond(result.todo)
            }

            delete("/{subtaskId}") {
                val username = call.username()
                val todoId = call.uuidParam() ?: return@delete
                val subtaskId = call.uuidParam("subtaskId") ?: return@delete
                val result = transaction {
                    // a subtask under a foreign private todo is as hidden as a missing one -> same 404
                    if (!parentTodoVisibleTo(todoId, username)) return@transaction null
                    val deleted = TodoSubtasksTable.deleteWhere {
                        (TodoSubtasksTable.id eq subtaskId) and (TodoSubtasksTable.todoId eq todoId)
                    }
                    if (deleted == 0) return@transaction null
                    todoWithVisibility(todoId)
                }
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Subtask not found"))
                    return@delete
                }
                broadcastTodoSubtaskChange(result)
                call.respond(result.todo)
            }
        }
    }

    webSocket("/ws/todos") {
        WsSessionManager.add(TODO_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(TODO_WS_CHANNEL, this)
        }
    }
}

/**
 * A todo plus the visibility of its list before and after a mutation. [spawned] carries the next
 * recurrence instance created when a recurring todo is completed, to broadcast separately.
 */
private class TodoMutation(
    val todo: TodoDto,
    val wasShared: Boolean,
    val isShared: Boolean,
    val spawned: TodoDto? = null,
    val spawnedShared: Boolean = false,
)

/**
 * The "todos" WS channel reaches both users, so a todo in someone else's private list must never be
 * pushed over it. A todo's visibility is its list's: a todo with no list or in a SHARED list is
 * visible to both; a todo in a PRIVATE list only to that list's owner. Must run inside a transaction.
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
 * Must run inside a transaction.
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
 * counts as visible, mirroring [listIsShared]. Must run inside a transaction.
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
 * Must run inside a transaction.
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

private suspend fun broadcastTodoCreate(shared: Boolean, todo: TodoDto) {
    if (shared) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(WsMessage("TODO_CREATED", todo)))
    }
}

/**
 * Enforces list visibility on the shared channel and translates visibility transitions for the
 * *other* client: a todo entering a private list looks like a deletion; a todo that is (or becomes)
 * shared looks like an upsert; a todo that stays private is never sent.
 */
internal suspend fun broadcastTodoUpdate(wasShared: Boolean, isShared: Boolean, todo: TodoDto) {
    val type = when {
        isShared -> "TODO_UPDATED"   // other client upserts (covers private -> shared too)
        wasShared -> "TODO_DELETED"  // shared -> private: remove it for the other client
        else -> return               // stays private: nothing to share
    }
    WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(WsMessage(type, todo)))
}

private suspend fun broadcastTodoDelete(shared: Boolean, todo: TodoDto) {
    if (shared) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(WsMessage("TODO_DELETED", todo)))
    }
}

private suspend fun broadcastTodoSubtaskChange(mutation: TodoMutation) =
    broadcastTodoUpdate(mutation.wasShared, mutation.isShared, mutation.todo)

private suspend fun broadcastListCreate(list: TodoListDto) {
    if (list.visibility != VISIBILITY_PRIVATE) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(TodoListWsMessage("TODO_LIST_CREATED", list)))
    }
}

/** Same visibility rules as todos, applied to the list's own metadata (its name leaks otherwise). */
private suspend fun broadcastListUpdate(
    wasShared: Boolean,
    list: TodoListDto,
    revealedTodos: List<TodoDto>,
) {
    val isShared = list.visibility != VISIBILITY_PRIVATE
    val type = when {
        isShared && wasShared -> "TODO_LIST_UPDATED"  // normal edit: other client replaces it
        isShared -> "TODO_LIST_CREATED"               // private -> shared: other client gains it
        wasShared -> "TODO_LIST_DELETED"              // shared -> private: other client drops list + todos
        else -> return                                // stays private: nothing to share
    }
    WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(TodoListWsMessage(type, list)))
    // private -> shared: the TODO_LIST_CREATED above only carries list metadata. The list's todos were
    // never broadcast while it was private, so the other client would render it empty until a manual
    // reload. Replay each as a TODO_CREATED upsert (the frontend handler is idempotent). See issue #75.
    if (isShared && !wasShared) {
        revealedTodos.forEach { todo ->
            WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(WsMessage("TODO_CREATED", todo)))
        }
    }
}

private suspend fun broadcastListDelete(list: TodoListDto) {
    if (list.visibility != VISIBILITY_PRIVATE) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, appJson.encodeToString(TodoListWsMessage("TODO_LIST_DELETED", list)))
    }
}

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
    if (status !in VALID_TODO_STATUSES) {
        return ErrorResponse("INVALID_STATUS", "status must be INBOX, PLANNED or DONE")
    }
    if (priority != null && priority !in VALID_TODO_PRIORITIES) {
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
        if (recurrenceFreq !in Recurrence.FREQUENCIES) {
            return ErrorResponse("INVALID_RECURRENCE", "recurrence.freq must be DAILY, WEEKLY or MONTHLY")
        }
        if (recurrenceInterval != null && recurrenceInterval !in 1..Recurrence.MAX_INTERVAL) {
            return ErrorResponse("INVALID_RECURRENCE", "recurrence.interval must be between 1 and ${Recurrence.MAX_INTERVAL}")
        }
        if (dueDate.isNullOrBlank()) {
            return ErrorResponse("INVALID_RECURRENCE", "a recurring todo needs a dueDate as its schedule anchor")
        }
    }
    if (status == "PLANNED" && assignees.isEmpty() && dueDate.isNullOrBlank()) {
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

internal fun ResultRow.toTodoDto(): TodoDto {
    val todoId = this[TodosTable.id]
    val subtasks = TodoSubtasksTable.selectAll()
        .where { TodoSubtasksTable.todoId eq todoId }
        .orderBy(TodoSubtasksTable.sortOrder to SortOrder.ASC)
        .map { it.toSubtaskDto() }
    return TodoDto(
        id = todoId.toString(),
        title = this[TodosTable.title],
        description = this[TodosTable.description],
        status = this[TodosTable.status],
        assignees = loadTodoAssignees(todoId),
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
