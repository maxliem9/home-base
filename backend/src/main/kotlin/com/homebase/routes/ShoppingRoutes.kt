package com.homebase.routes

import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
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

private const val SHOPPING_WS_CHANNEL = "shopping"

fun Route.shoppingRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun broadcastItem(type: String, item: ShoppingItemDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, json.encodeToString(ShoppingWsMessage(type, item)))

    suspend fun broadcastList(type: String, list: ShoppingListDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, json.encodeToString(ShoppingListWsMessage(type, list)))

    route("/shopping") {
        // ---- Lists (registered before /{id} so the static segment wins) ----
        route("/lists") {
            get {
                val lists = transaction {
                    ShoppingListsTable.selectAll()
                        .orderBy(ShoppingListsTable.createdAt to SortOrder.ASC)
                        .map { it.toListDto() }
                }
                call.respond(lists)
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val username = principal.payload.getClaim("username").asString()
                val req = call.receive<CreateShoppingListRequest>()
                if (req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_LIST", "name must not be blank"))
                    return@post
                }
                val list = transaction {
                    val id = UUID.randomUUID()
                    ShoppingListsTable.insert {
                        it[ShoppingListsTable.id] = id
                        it[name] = req.name.trim()
                        it[createdBy] = username
                        it[createdAt] = Instant.now()
                    }
                    ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.single().toListDto()
                }
                broadcastList("SHOPPING_LIST_CREATED", list)
                call.respond(HttpStatusCode.Created, list)
            }

            put("/{id}") {
                val id = call.uuidParam() ?: return@put
                val req = call.receive<UpdateShoppingListRequest>()
                if (req.name != null && req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_LIST", "name must not be blank"))
                    return@put
                }
                val list = transaction {
                    ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
                    ShoppingListsTable.update({ ShoppingListsTable.id eq id }) {
                        req.name?.let { v -> it[name] = v.trim() }
                    }
                    ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.single().toListDto()
                }
                if (list == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@put
                }
                broadcastList("SHOPPING_LIST_UPDATED", list)
                call.respond(list)
            }

            delete("/{id}") {
                val id = call.uuidParam() ?: return@delete
                val deleted = transaction {
                    val existing = ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.singleOrNull()
                        ?: return@transaction null
                    // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
                    ShoppingItemsTable.deleteWhere { ShoppingItemsTable.listId eq id }
                    ShoppingListsTable.deleteWhere { ShoppingListsTable.id eq id }
                    existing.toListDto()
                }
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@delete
                }
                broadcastList("SHOPPING_LIST_DELETED", deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

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
            if (req.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SHOPPING_ITEM", "name must not be blank"))
                return@post
            }
            val listId = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.listId != null && req.listId.isNotBlank() && listId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
                return@post
            }

            val item = transaction {
                if (listId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }.empty()) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }
                val id = UUID.randomUUID()
                ShoppingItemsTable.insert {
                    it[ShoppingItemsTable.id] = id
                    it[name] = req.name
                    it[ShoppingItemsTable.listId] = listId
                    it[checked] = false
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto()
            }

            if (item is ErrorResponse) {
                call.respond(HttpStatusCode.BadRequest, item)
                return@post
            }
            broadcastItem("SHOPPING_CREATED", item as ShoppingItemDto)
            call.respond(HttpStatusCode.Created, item)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateShoppingItemRequest>()
            if (req.name?.isBlank() == true) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SHOPPING_ITEM", "name must not be blank"))
                return@put
            }
            // null = unchanged, "" = clear, else target list id (must exist)
            val targetListId: UUID? = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.listId != null && req.listId.isNotBlank() && targetListId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
                return@put
            }

            val item = transaction {
                ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                if (targetListId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq targetListId }.empty()) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }

                ShoppingItemsTable.update({ ShoppingItemsTable.id eq id }) {
                    req.name?.let { v -> it[name] = v }
                    req.listId?.let { _ -> it[listId] = targetListId }
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
            if (item is ErrorResponse) {
                call.respond(HttpStatusCode.BadRequest, item)
                return@put
            }

            broadcastItem("SHOPPING_UPDATED", item as ShoppingItemDto)
            call.respond(item)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
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
            broadcastItem("SHOPPING_DELETED", deletedItem)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/shopping") {
        WsSessionManager.add(SHOPPING_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(SHOPPING_WS_CHANNEL, this)
        }
    }
}

private fun ResultRow.toListDto() = ShoppingListDto(
    id = this[ShoppingListsTable.id].toString(),
    name = this[ShoppingListsTable.name],
    createdBy = this[ShoppingListsTable.createdBy],
    createdAt = this[ShoppingListsTable.createdAt].toString(),
)

private fun ResultRow.toDto() = ShoppingItemDto(
    id = this[ShoppingItemsTable.id].toString(),
    name = this[ShoppingItemsTable.name],
    listId = this[ShoppingItemsTable.listId]?.toString(),
    checked = this[ShoppingItemsTable.checked],
    createdBy = this[ShoppingItemsTable.createdBy],
    createdAt = this[ShoppingItemsTable.createdAt].toString(),
    checkedAt = this[ShoppingItemsTable.checkedAt]?.toString()
)
