package com.homebase.routes

import com.homebase.db.ShoppingItemsTable
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
import java.util.UUID

fun Route.shoppingRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    route("/shopping") {
        get {
            val items = transaction {
                ShoppingItemsTable.selectAll().map { it.toDto() }
            }
            call.respond(items)
        }

        post {
            val principal = call.principal<JWTPrincipal>()!!
            val username = principal.payload.getClaim("username").asString()
            val req = call.receive<CreateShoppingItemRequest>()

            val item = transaction {
                val id = UUID.randomUUID()
                ShoppingItemsTable.insert {
                    it[ShoppingItemsTable.id] = id
                    it[name] = req.name
                    it[category] = req.category
                    it[checked] = false
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto()
            }

            WsSessionManager.broadcast(json.encodeToString(ShoppingWsMessage("SHOPPING_CREATED", item)))
            call.respond(HttpStatusCode.Created, item)
        }

        put("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<UpdateShoppingItemRequest>()

            val item = transaction {
                ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.singleOrNull()
                    ?: return@transaction null

                ShoppingItemsTable.update({ ShoppingItemsTable.id eq id }) {
                    req.name?.let { v -> it[name] = v }
                    req.category?.let { v -> it[category] = v }
                    req.checked?.let { v ->
                        it[checked] = v
                        it[checkedAt] = if (v) Instant.now() else null
                    }
                }
                ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto()
            }

            if (item == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Shopping item not found"))
                return@put
            }

            WsSessionManager.broadcast(json.encodeToString(ShoppingWsMessage("SHOPPING_UPDATED", item)))
            call.respond(item)
        }

        delete("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val deletedItem = transaction {
                val existing = ShoppingItemsTable.selectAll()
                    .where { ShoppingItemsTable.id eq id }
                    .singleOrNull()
                    ?: return@transaction null
                ShoppingItemsTable.deleteWhere { ShoppingItemsTable.id eq id }
                existing.toDto()
            }
            if (deletedItem == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Shopping item not found"))
                return@delete
            }
            WsSessionManager.broadcast(json.encodeToString(ShoppingWsMessage("SHOPPING_DELETED", deletedItem)))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/shopping") {
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

private fun ResultRow.toDto() = ShoppingItemDto(
    id = this[ShoppingItemsTable.id].toString(),
    name = this[ShoppingItemsTable.name],
    category = this[ShoppingItemsTable.category],
    checked = this[ShoppingItemsTable.checked],
    createdBy = this[ShoppingItemsTable.createdBy],
    createdAt = this[ShoppingItemsTable.createdAt].toString(),
    checkedAt = this[ShoppingItemsTable.checkedAt]?.toString()
)
