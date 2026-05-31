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

            WsSessionManager.broadcast(json.encodeToString(WsMessage("TODO_CREATED", todo)))
            call.respond(HttpStatusCode.Created, todo)
        }

        put("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<UpdateTodoRequest>()

            val todo = transaction {
                TodosTable.selectAll().where { TodosTable.id eq id }.singleOrNull()
                    ?: return@transaction null

                TodosTable.update({ TodosTable.id eq id }) {
                    req.title?.let { v -> it[title] = v }
                    req.description?.let { v -> it[description] = v }
                    req.assignee?.let { v -> it[assignee] = v }
                    req.dueDate?.let { v -> it[dueDate] = LocalDate.parse(v) }
                    req.priority?.let { v -> it[priority] = v }
                    req.status?.let { v ->
                        it[status] = v
                        if (v == "DONE") it[doneAt] = Instant.now()
                    }
                }
                TodosTable.selectAll().where { TodosTable.id eq id }.single().toDto()
            }

            if (todo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@put
            }

            WsSessionManager.broadcast(json.encodeToString(WsMessage("TODO_UPDATED", todo)))
            call.respond(todo)
        }

        delete("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val deleted = transaction {
                TodosTable.deleteWhere { TodosTable.id eq id }
            }
            if (deleted == 0) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/todos") {
        WsSessionManager.add(this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(this)
        }
    }
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
