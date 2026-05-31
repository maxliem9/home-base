package com.homebase.routes

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
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val TODO_WS_CHANNEL = "todos"
private val VALID_TODO_STATUSES = setOf("INBOX", "PLANNED", "DONE")
private val VALID_TODO_PRIORITIES = setOf("LOW", "MEDIUM", "HIGH")

fun Route.todoRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    route("/todos") {
        get {
            val todos = transaction {
                TodosTable.selectAll().map { it.toDto() }
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

            val todo = transaction {
                val id = UUID.randomUUID()
                TodosTable.insert {
                    it[TodosTable.id] = id
                    it[title] = req.title
                    it[description] = req.description
                    it[status] = "INBOX"
                    it[assignee] = req.assignee
                    it[dueDate] = req.dueDate?.let { d -> LocalDate.parse(d) }
                    it[priority] = req.priority
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                TodosTable.selectAll().where { TodosTable.id eq id }.single().toDto()
            }

            WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage("TODO_CREATED", todo)))
            call.respond(HttpStatusCode.Created, todo)
        }

        put("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<UpdateTodoRequest>()

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

                TodosTable.update({ TodosTable.id eq id }) {
                    req.title?.let { v -> it[title] = v }
                    req.description?.let { v -> it[description] = v }
                    req.assignee?.let { v -> it[assignee] = v }
                    req.dueDate?.let { v -> it[dueDate] = LocalDate.parse(v) }
                    req.priority?.let { v -> it[priority] = v }
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

            WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage("TODO_UPDATED", todo as TodoDto)))
            call.respond(todo)
        }

        delete("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val deletedTodo = transaction {
                val existing = TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                TodosTable.deleteWhere { TodosTable.id eq id }
                existing.toDto()
            }
            if (deletedTodo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@delete
            }
            WsSessionManager.broadcast(TODO_WS_CHANNEL, json.encodeToString(WsMessage("TODO_DELETED", deletedTodo)))
            call.respond(HttpStatusCode.NoContent)
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
    dueDate?.let { LocalDate.parse(it) }
    return null
}

private fun ResultRow.toDto() = TodoDto(
    id = this[TodosTable.id].toString(),
    title = this[TodosTable.title],
    description = this[TodosTable.description],
    status = this[TodosTable.status],
    assignee = this[TodosTable.assignee],
    dueDate = this[TodosTable.dueDate]?.toString(),
    priority = this[TodosTable.priority],
    createdBy = this[TodosTable.createdBy],
    createdAt = this[TodosTable.createdAt].toString(),
    doneAt = this[TodosTable.doneAt]?.toString()
)
