package com.homebase.routes

import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val TODO_WS_CHANNEL = "todos"
private const val VISIBILITY_SHARED = "SHARED"
private const val VISIBILITY_PRIVATE = "PRIVATE"
private const val DEFAULT_LIST_VISIBILITY = VISIBILITY_SHARED
private val VALID_TODO_STATUSES = setOf("INBOX", "PLANNED", "DONE")
private val VALID_TODO_PRIORITIES = setOf("LOW", "MEDIUM", "HIGH")
private val VALID_LIST_VISIBILITIES = setOf(VISIBILITY_SHARED, VISIBILITY_PRIVATE)

fun Route.todoRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    route("/todos") {
        // ---- Lists (registered before /{id} so the static segment wins) ----
        route("/lists") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
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
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
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
                broadcastListCreate(json, list)
                call.respond(HttpStatusCode.Created, list)
            }

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
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
                            .map { it.toDto() }
                    } else emptyList()
                    Triple(wasShared, updated, revealedTodos)
                }
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@put
                }
                val (wasShared, list, revealedTodos) = result
                broadcastListUpdate(json, wasShared, list, revealedTodos)
                call.respond(list)
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
                val id = call.uuidParam() ?: return@delete
                val deleted = transaction {
                    val existing = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
                    // Only the owner may delete a private list (see PUT above).
                    if (existing[TodoListsTable.visibility] == VISIBILITY_PRIVATE && existing[TodoListsTable.createdBy] != username) {
                        return@transaction null
                    }
                    // delete the list's todos and their subtasks (mirrors ON DELETE CASCADE for
                    // the H2 test DB, which models list_id without a FK; real Postgres cascades via V12)
                    val todoIds = TodosTable.selectAll().where { TodosTable.listId eq id }
                        .map { it[TodosTable.id] }
                    if (todoIds.isNotEmpty()) {
                        TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId inList todoIds }
                        TodosTable.deleteWhere { TodosTable.listId eq id }
                    }
                    TodoListsTable.deleteWhere { TodoListsTable.id eq id }
                    existing.toListDto()
                }
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@delete
                }
                broadcastListDelete(json, deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get {
            val principal = call.principal<JWTPrincipal>()!!
            val username = principal.payload.getClaim("username").asString()
            val todos = transaction {
                // hide todos that live in someone else's private list
                val hiddenListIds = TodoListsTable.selectAll()
                    .where { (TodoListsTable.visibility eq VISIBILITY_PRIVATE) and (TodoListsTable.createdBy neq username) }
                    .map { it[TodoListsTable.id] }
                    .toSet()
                TodosTable.selectAll()
                    .map { it.toDto() }
                    .filter { it.listId == null || UUID.fromString(it.listId) !in hiddenListIds }
            }
            call.respond(todos)
        }

        post {
            val principal = call.principal<JWTPrincipal>()!!
            val username = principal.payload.getClaim("username").asString()
            val req = call.receive<CreateTodoRequest>()
            val validationError = validateTodoInput(
                title = req.title,
                status = "INBOX",
                assignee = req.assignee,
                dueDate = req.dueDate,
                priority = req.priority,
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

            val result = transaction {
                // resolve the target list's visibility, enforcing ownership: a foreign private list
                // is treated as non-existent so it can neither be written into nor probed (#73)
                val listVisibility = if (listId != null) {
                    writableListVisibility(listId, username)
                        ?: return@transaction ErrorResponse("NOT_FOUND", "List not found")
                } else null
                val id = UUID.randomUUID()
                TodosTable.insert {
                    it[TodosTable.id] = id
                    it[title] = req.title
                    it[description] = req.description
                    it[status] = "INBOX"
                    it[assignee] = req.assignee
                    it[dueDate] = req.dueDate?.let { d -> LocalDate.parse(d) }
                    it[priority] = req.priority
                    it[TodosTable.listId] = listId
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                val dto = TodosTable.selectAll().where { TodosTable.id eq id }.single().toDto()
                val shared = listVisibility != VISIBILITY_PRIVATE
                TodoMutation(dto, wasShared = shared, isShared = shared)
            }

            if (result is ErrorResponse) {
                // the only in-transaction error is the unknown/foreign list -> 404 (no existence oracle)
                call.respond(HttpStatusCode.NotFound, result)
                return@post
            }
            result as TodoMutation
            broadcastTodoCreate(json, result.isShared, result.todo)
            call.respond(HttpStatusCode.Created, result.todo)
        }

        put("/{id}") {
            val principal = call.principal<JWTPrincipal>()!!
            val username = principal.payload.getClaim("username").asString()
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
                val nextAssignee = req.assignee ?: existing[TodosTable.assignee]
                val nextDueDate = req.dueDate ?: existing[TodosTable.dueDate]?.toString()
                validateTodoInput(
                    title = req.title ?: existing[TodosTable.title],
                    status = nextStatus,
                    assignee = nextAssignee,
                    dueDate = nextDueDate,
                    priority = req.priority ?: existing[TodosTable.priority],
                )?.let { return@transaction it }
                // moving into a list requires it to be writable: unknown or foreign-private -> 404 (#73)
                if (targetListId != null && writableListVisibility(targetListId, username) == null) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }

                TodosTable.update({ TodosTable.id eq id }) {
                    req.title?.let { v -> it[title] = v }
                    req.description?.let { v -> it[description] = v }
                    req.assignee?.let { v -> it[assignee] = v }
                    req.dueDate?.let { v -> it[dueDate] = LocalDate.parse(v) }
                    req.priority?.let { v -> it[priority] = v }
                    req.listId?.let { _ -> it[listId] = targetListId }
                    req.status?.let { v ->
                        it[status] = v
                        it[doneAt] = if (v == "DONE") Instant.now() else null
                    }
                }
                // null = unchanged keeps the old list; "" cleared it (targetListId == null)
                val newListId = if (req.listId != null) targetListId else existing[TodosTable.listId]
                val dto = TodosTable.selectAll().where { TodosTable.id eq id }.single().toDto()
                TodoMutation(dto, wasShared = wasShared, isShared = listIsShared(newListId))
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
            broadcastTodoUpdate(json, result.wasShared, result.isShared, result.todo)
            call.respond(result.todo)
        }

        delete("/{id}") {
            val principal = call.principal<JWTPrincipal>()!!
            val username = principal.payload.getClaim("username").asString()
            val id = call.uuidParam() ?: return@delete
            val result = transaction {
                val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                // a todo in someone else's private list is invisible; treat it as non-existent (#73)
                if (!listVisibleTo(existing[TodosTable.listId], username)) return@transaction null
                val shared = listIsShared(existing[TodosTable.listId])
                // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
                TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId eq id }
                TodosTable.deleteWhere { TodosTable.id eq id }
                existing.toDto() to shared
            }
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@delete
            }
            val (deletedTodo, shared) = result
            broadcastTodoDelete(json, shared, deletedTodo)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Subtasks ----
        route("/{id}/subtasks") {
            post {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
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
                broadcastTodoSubtaskChange(json, result)
                call.respond(HttpStatusCode.Created, result.todo)
            }

            put("/{subtaskId}") {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
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
                broadcastTodoSubtaskChange(json, result)
                call.respond(result.todo)
            }

            delete("/{subtaskId}") {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
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
                broadcastTodoSubtaskChange(json, result)
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

/** A todo plus the visibility of its list before and after a mutation. */
private class TodoMutation(val todo: TodoDto, val wasShared: Boolean, val isShared: Boolean)

/**
 * The "todos" WS channel reaches both users, so a todo in someone else's private list must never be
 * pushed over it. A todo's visibility is its list's: a todo with no list or in a SHARED list is
 * visible to both; a todo in a PRIVATE list only to that list's owner. Must run inside a transaction.
 */
private fun listIsShared(listId: UUID?): Boolean {
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
    return TodoMutation(row.toDto(), wasShared = shared, isShared = shared)
}

private suspend fun broadcastTodoCreate(json: Json, shared: Boolean, todo: TodoDto) {
    if (shared) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage("TODO_CREATED", todo)))
    }
}

/**
 * Enforces list visibility on the shared channel and translates visibility transitions for the
 * *other* client: a todo entering a private list looks like a deletion; a todo that is (or becomes)
 * shared looks like an upsert; a todo that stays private is never sent.
 */
private suspend fun broadcastTodoUpdate(json: Json, wasShared: Boolean, isShared: Boolean, todo: TodoDto) {
    val type = when {
        isShared -> "TODO_UPDATED"   // other client upserts (covers private -> shared too)
        wasShared -> "TODO_DELETED"  // shared -> private: remove it for the other client
        else -> return               // stays private: nothing to share
    }
    WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage(type, todo)))
}

private suspend fun broadcastTodoDelete(json: Json, shared: Boolean, todo: TodoDto) {
    if (shared) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage("TODO_DELETED", todo)))
    }
}

private suspend fun broadcastTodoSubtaskChange(json: Json, mutation: TodoMutation) =
    broadcastTodoUpdate(json, mutation.wasShared, mutation.isShared, mutation.todo)

private suspend fun broadcastListCreate(json: Json, list: TodoListDto) {
    if (list.visibility != VISIBILITY_PRIVATE) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(TodoListWsMessage("TODO_LIST_CREATED", list)))
    }
}

/** Same visibility rules as todos, applied to the list's own metadata (its name leaks otherwise). */
private suspend fun broadcastListUpdate(
    json: Json,
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
    WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(TodoListWsMessage(type, list)))
    // private -> shared: the TODO_LIST_CREATED above only carries list metadata. The list's todos were
    // never broadcast while it was private, so the other client would render it empty until a manual
    // reload. Replay each as a TODO_CREATED upsert (the frontend handler is idempotent). See issue #75.
    if (isShared && !wasShared) {
        revealedTodos.forEach { todo ->
            WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage("TODO_CREATED", todo)))
        }
    }
}

private suspend fun broadcastListDelete(json: Json, list: TodoListDto) {
    if (list.visibility != VISIBILITY_PRIVATE) {
        WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(TodoListWsMessage("TODO_LIST_DELETED", list)))
    }
}

private fun validateTodoInput(
    title: String,
    status: String,
    assignee: String?,
    dueDate: String?,
    priority: String?,
): ErrorResponse? {
    if (title.isBlank()) return ErrorResponse("INVALID_TODO", "title must not be blank")
    if (status !in VALID_TODO_STATUSES) {
        return ErrorResponse("INVALID_STATUS", "status must be INBOX, PLANNED or DONE")
    }
    if (priority != null && priority !in VALID_TODO_PRIORITIES) {
        return ErrorResponse("INVALID_PRIORITY", "priority must be LOW, MEDIUM or HIGH")
    }
    if (status == "PLANNED" && assignee.isNullOrBlank() && dueDate.isNullOrBlank()) {
        return ErrorResponse("INVALID_TODO", "PLANNED todos need an assignee or dueDate")
    }
    if (dueDate != null) {
        runCatching { LocalDate.parse(dueDate) }.getOrElse {
            return ErrorResponse("INVALID_DUE_DATE", "dueDate must be in YYYY-MM-DD format")
        }
    }
    return null
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

private fun ResultRow.toDto(): TodoDto {
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
        assignee = this[TodosTable.assignee],
        dueDate = this[TodosTable.dueDate]?.toString(),
        priority = this[TodosTable.priority],
        listId = this[TodosTable.listId]?.toString(),
        subtasks = subtasks,
        createdBy = this[TodosTable.createdBy],
        createdAt = this[TodosTable.createdAt].toString(),
        doneAt = this[TodosTable.doneAt]?.toString(),
    )
}
