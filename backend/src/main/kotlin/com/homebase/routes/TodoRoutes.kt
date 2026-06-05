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
private const val DEFAULT_LIST_VISIBILITY = "SHARED"
private val VALID_TODO_STATUSES = setOf("INBOX", "PLANNED", "DONE")
private val VALID_TODO_PRIORITIES = setOf("LOW", "MEDIUM", "HIGH")
private val VALID_LIST_VISIBILITIES = setOf("SHARED", "PRIVATE")

fun Route.todoRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun broadcastTodo(type: String, todo: TodoDto) =
        WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage(type, todo)))

    suspend fun broadcastList(type: String, list: TodoListDto) =
        WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(TodoListWsMessage(type, list)))

    route("/todos") {
        // ---- Lists (registered before /{id} so the static segment wins) ----
        route("/lists") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
                val lists = transaction {
                    // shared lists are visible to everyone; private lists only to their creator
                    TodoListsTable.selectAll()
                        .where { (TodoListsTable.visibility eq "SHARED") or (TodoListsTable.createdBy eq username) }
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
                broadcastList("TODO_LIST_CREATED", list)
                call.respond(HttpStatusCode.Created, list)
            }

            put("/{id}") {
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
                val list = transaction {
                    TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
                    TodoListsTable.update({ TodoListsTable.id eq id }) {
                        req.name?.let { v -> it[name] = v.trim() }
                        req.visibility?.let { v -> it[visibility] = v }
                    }
                    TodoListsTable.selectAll().where { TodoListsTable.id eq id }.single().toListDto()
                }
                if (list == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@put
                }
                broadcastList("TODO_LIST_UPDATED", list)
                call.respond(list)
            }

            delete("/{id}") {
                val id = call.uuidParam() ?: return@delete
                val deleted = transaction {
                    val existing = TodoListsTable.selectAll().where { TodoListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
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
                broadcastList("TODO_LIST_DELETED", deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get {
            val principal = call.principal<JWTPrincipal>()!!
            val username = principal.payload.getClaim("username").asString()
            val todos = transaction {
                // hide todos that live in someone else's private list
                val hiddenListIds = TodoListsTable.selectAll()
                    .where { (TodoListsTable.visibility eq "PRIVATE") and (TodoListsTable.createdBy neq username) }
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

            val todo = transaction {
                if (listId != null && TodoListsTable.selectAll().where { TodoListsTable.id eq listId }.empty()) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }
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
                TodosTable.selectAll().where { TodosTable.id eq id }.single().toDto()
            }

            if (todo is ErrorResponse) {
                call.respond(HttpStatusCode.BadRequest, todo)
                return@post
            }
            broadcastTodo("TODO_CREATED", todo as TodoDto)
            call.respond(HttpStatusCode.Created, todo)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateTodoRequest>()
            // null = unchanged, "" = clear, else target list id (must exist)
            val targetListId: UUID? = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.listId != null && req.listId.isNotBlank() && targetListId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
                return@put
            }

            val todo = transaction {
                val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null
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
                if (targetListId != null && TodoListsTable.selectAll().where { TodoListsTable.id eq targetListId }.empty()) {
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
                TodosTable.selectAll().where { TodosTable.id eq id }.single().toDto()
            }

            if (todo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@put
            }
            if (todo is ErrorResponse) {
                call.respond(HttpStatusCode.BadRequest, todo)
                return@put
            }

            broadcastTodo("TODO_UPDATED", todo as TodoDto)
            call.respond(todo)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deletedTodo = transaction {
                val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
                TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId eq id }
                TodosTable.deleteWhere { TodosTable.id eq id }
                existing.toDto()
            }
            if (deletedTodo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@delete
            }
            broadcastTodo("TODO_DELETED", deletedTodo)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Subtasks ----
        route("/{id}/subtasks") {
            post {
                val todoId = call.uuidParam() ?: return@post
                val req = call.receive<CreateSubtaskRequest>()
                if (req.title.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SUBTASK", "title must not be blank"))
                    return@post
                }
                val todo = transaction {
                    if (TodosTable.selectAll().where { TodosTable.id eq todoId }.empty()) return@transaction null
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
                    TodosTable.selectAll().where { TodosTable.id eq todoId }.single().toDto()
                }
                if (todo == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                    return@post
                }
                broadcastTodo("TODO_UPDATED", todo)
                call.respond(HttpStatusCode.Created, todo)
            }

            put("/{subtaskId}") {
                val todoId = call.uuidParam() ?: return@put
                val subtaskId = call.uuidParam("subtaskId") ?: return@put
                val req = call.receive<UpdateSubtaskRequest>()
                if (req.title != null && req.title.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SUBTASK", "title must not be blank"))
                    return@put
                }
                val todo = transaction {
                    val exists = TodoSubtasksTable.selectAll()
                        .where { (TodoSubtasksTable.id eq subtaskId) and (TodoSubtasksTable.todoId eq todoId) }
                        .empty().not()
                    if (!exists) return@transaction null
                    TodoSubtasksTable.update({ TodoSubtasksTable.id eq subtaskId }) {
                        req.title?.let { v -> it[title] = v.trim() }
                        req.done?.let { v -> it[done] = v }
                    }
                    TodosTable.selectAll().where { TodosTable.id eq todoId }.single().toDto()
                }
                if (todo == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Subtask not found"))
                    return@put
                }
                broadcastTodo("TODO_UPDATED", todo)
                call.respond(todo)
            }

            delete("/{subtaskId}") {
                val todoId = call.uuidParam() ?: return@delete
                val subtaskId = call.uuidParam("subtaskId") ?: return@delete
                val todo = transaction {
                    val deleted = TodoSubtasksTable.deleteWhere {
                        (TodoSubtasksTable.id eq subtaskId) and (TodoSubtasksTable.todoId eq todoId)
                    }
                    if (deleted == 0) return@transaction null
                    TodosTable.selectAll().where { TodosTable.id eq todoId }.single().toDto()
                }
                if (todo == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Subtask not found"))
                    return@delete
                }
                broadcastTodo("TODO_UPDATED", todo)
                call.respond(todo)
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
