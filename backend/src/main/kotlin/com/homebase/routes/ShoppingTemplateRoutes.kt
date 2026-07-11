package com.homebase.routes

import com.homebase.db.dbQuery
import com.homebase.db.ShoppingTemplateItemsTable
import com.homebase.db.ShoppingTemplatesTable
import com.homebase.model.*
import com.homebase.plugins.appJson
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

// Named "standard/template shopping lists" (#215). Lean tables of their own; applying a template
// reuses the existing /shopping/batch add (a client concern), so there is no apply endpoint here.
// Broadcasts ride the SAME shopping WS channel as list/item mutations so a single subscription on
// the client sees template changes too. Shared household model — both users manage all templates,
// no ownership check (consistent with the shopping lists themselves).
//
// The channel literal mirrors ShoppingRoutes.SHOPPING_WS_CHANNEL (which is private there); both
// must stay "shopping". Broadcast routing is keyed by this string, so matching the literal is what
// couples the two files.
private const val SHOPPING_WS_CHANNEL = "shopping"

fun Route.shoppingTemplateRoutes() {
    suspend fun broadcast(type: String, template: ShoppingTemplateDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingTemplateWsMessage(type, template)))

    route("/shopping/templates") {
        // All templates with their embedded items. Newest first (like the recipe/list reads use a
        // stable order); items inside each template are ordered by sort_order.
        get {
            val templates = dbQuery {
                ShoppingTemplatesTable.selectAll()
                    .orderBy(ShoppingTemplatesTable.createdAt to SortOrder.ASC)
                    .map { it.toTemplateDto() }
            }
            call.respond(templates)
        }

        post {
            val username = call.username()
            val req = call.receive<CreateShoppingTemplateRequest>()
            if (req.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_TEMPLATE", "name must not be blank"))
                return@post
            }
            val template = dbQuery {
                val id = UUID.randomUUID()
                ShoppingTemplatesTable.insert {
                    it[ShoppingTemplatesTable.id] = id
                    it[name] = req.name.trim()
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                insertItems(id, req.items)
                ShoppingTemplatesTable.selectAll().where { ShoppingTemplatesTable.id eq id }.single().toTemplateDto()
            }
            broadcast("SHOPPING_TEMPLATE_CREATED", template)
            call.respond(HttpStatusCode.Created, template)
        }

        // Update the name and REPLACE the items wholesale (delete + reinsert), mirroring how a
        // recipe update handles its embedded ingredients/steps. Items default null = leave as-is.
        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateShoppingTemplateRequest>()
            if (req.name != null && req.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_TEMPLATE", "name must not be blank"))
                return@put
            }
            val template = dbQuery {
                ShoppingTemplatesTable.selectAll().where { ShoppingTemplatesTable.id eq id }.singleOrNull()
                    ?: return@dbQuery null
                ShoppingTemplatesTable.update({ ShoppingTemplatesTable.id eq id }) {
                    req.name?.let { v -> it[name] = v.trim() }
                }
                req.items?.let { items ->
                    ShoppingTemplateItemsTable.deleteWhere { ShoppingTemplateItemsTable.templateId eq id }
                    insertItems(id, items)
                }
                ShoppingTemplatesTable.selectAll().where { ShoppingTemplatesTable.id eq id }.single().toTemplateDto()
            }
            if (template == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Template not found"))
                return@put
            }
            broadcast("SHOPPING_TEMPLATE_UPDATED", template)
            call.respond(template)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deleted = dbQuery {
                val existing = ShoppingTemplatesTable.selectAll().where { ShoppingTemplatesTable.id eq id }.singleOrNull()
                    ?: return@dbQuery null
                val dto = existing.toTemplateDto()
                // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
                ShoppingTemplateItemsTable.deleteWhere { ShoppingTemplateItemsTable.templateId eq id }
                ShoppingTemplatesTable.deleteWhere { ShoppingTemplatesTable.id eq id }
                dto
            }
            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Template not found"))
                return@delete
            }
            broadcast("SHOPPING_TEMPLATE_DELETED", deleted)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Must be called inside a transaction. Item order is taken from list position. Blank names are
// dropped (a template item is just a name; an empty one is meaningless).
private fun insertItems(templateId: UUID, items: List<TemplateItemInput>) {
    items.filter { it.name.isNotBlank() }.forEachIndexed { index, item ->
        ShoppingTemplateItemsTable.insert {
            it[id] = UUID.randomUUID()
            it[ShoppingTemplateItemsTable.templateId] = templateId
            it[name] = item.name.trim()
            it[sortOrder] = index
        }
    }
}

// Loads the template with its items (ordered by sort_order). Must be called inside a transaction.
private fun ResultRow.toTemplateDto(): ShoppingTemplateDto {
    val templateId = this[ShoppingTemplatesTable.id]
    val items = ShoppingTemplateItemsTable.selectAll()
        .where { ShoppingTemplateItemsTable.templateId eq templateId }
        .orderBy(ShoppingTemplateItemsTable.sortOrder, SortOrder.ASC)
        .map {
            ShoppingTemplateItemDto(
                id = it[ShoppingTemplateItemsTable.id].toString(),
                name = it[ShoppingTemplateItemsTable.name],
                sortOrder = it[ShoppingTemplateItemsTable.sortOrder]
            )
        }
    return ShoppingTemplateDto(
        id = templateId.toString(),
        name = this[ShoppingTemplatesTable.name],
        items = items,
        createdBy = this[ShoppingTemplatesTable.createdBy],
        createdAt = this[ShoppingTemplatesTable.createdAt].toString()
    )
}
