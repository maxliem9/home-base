package com.homebase.routes

import com.homebase.model.*
import com.homebase.service.ShoppingService
import com.homebase.shopping.GroceryCatalog
import com.homebase.ws.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

private const val SHOPPING_WS_CHANNEL = "shopping"

/**
 * HTTP surface for the shopping domain. Handlers parse the request (query/path/body, blank + UUID
 * shape checks), call [ShoppingService] for all persistence + business rules, then — after the
 * transaction has committed — broadcast. No handler touches a `Shopping*Table`/`dbQuery {}` (issue
 * #562, following the TodoService pattern of #546). The broadcast wire format is the generic
 * SyncEnvelope via broadcastSync (#552).
 */
fun Route.shoppingRoutes() {
    val service = ShoppingService()

    suspend fun broadcastItem(type: String, item: ShoppingItemDto) =
        WsSessionManager.broadcastSync(SHOPPING_WS_CHANNEL, type, item, ShoppingItemDto.serializer())

    suspend fun broadcastList(type: String, list: ShoppingListDto) =
        WsSessionManager.broadcastSync(SHOPPING_WS_CHANNEL, type, list, ShoppingListDto.serializer())

    suspend fun broadcastCategory(type: String, category: ShoppingCategoryDto?) =
        WsSessionManager.broadcastSync(SHOPPING_WS_CHANNEL, type, category, ShoppingCategoryDto.serializer())

    suspend fun broadcastRule(type: String, rule: ShoppingCategoryRuleDto?) =
        WsSessionManager.broadcastSync(SHOPPING_WS_CHANNEL, type, rule, ShoppingCategoryRuleDto.serializer())

    route("/shopping") {
        // ---- Lists (registered before /{id} so the static segment wins) ----
        route("/lists") {
            get {
                call.respond(service.listLists())
            }

            post {
                val username = call.username()
                val req = call.receive<CreateShoppingListRequest>()
                if (req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_LIST", "name must not be blank"))
                    return@post
                }
                val list = service.createList(req.name, req.ownCategories, username)
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
                val list = service.updateList(id, req)
                if (list == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@put
                }
                broadcastList("SHOPPING_LIST_UPDATED", list)
                call.respond(list)
            }

            delete("/{id}") {
                val id = call.uuidParam() ?: return@delete
                val deleted = service.deleteList(id)
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@delete
                }
                broadcastList("SHOPPING_LIST_DELETED", deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // ---- Categories (editable catalog, #411) ----
        route("/categories") {
            get {
                val scope = call.categoryScopeListId() ?: return@get
                call.respond(service.categories(scope.value))
            }

            post {
                val req = call.receive<CreateShoppingCategoryRequest>()
                val label = req.label.trim()
                val emoji = req.emoji.trim()
                if (label.isBlank() || emoji.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "label and emoji must not be blank"))
                    return@post
                }
                val scope = call.categoryScopeListId() ?: return@post
                val created = service.createCategory(scope.value, label, emoji, req.sortOrder)
                if (created == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    return@post
                }
                broadcastCategory("SHOPPING_CATEGORY_CHANGED", created)
                call.respond(HttpStatusCode.Created, created)
            }

            put("/{key}") {
                val key = call.parameters["key"]?.takeIf { it.isNotBlank() } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "key required")); return@put
                }
                val req = call.receive<UpdateShoppingCategoryRequest>()
                if (req.label != null && req.label.isBlank() || req.emoji != null && req.emoji.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "label/emoji must not be blank")); return@put
                }
                val updated = service.updateCategory(key, req)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Category not found")); return@put
                }
                broadcastCategory("SHOPPING_CATEGORY_CHANGED", updated)
                call.respond(updated)
            }

            delete("/{key}") {
                val key = call.parameters["key"]?.takeIf { it.isNotBlank() } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "key required")); return@delete
                }
                if (key == GroceryCatalog.OTHER) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("CATEGORY_PROTECTED", "the fallback category cannot be deleted"))
                    return@delete
                }
                val deleted = service.deleteCategory(key)
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Category not found")); return@delete
                }
                broadcastCategory("SHOPPING_CATEGORY_CHANGED", deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // ---- Category rules (editable auto-resolve dictionary, #411 PR B / #501) ----
        route("/category-rules") {
            get {
                val scope = call.categoryScopeListId() ?: return@get
                val rules = service.rules(scope.value)
                if (rules == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found")); return@get
                }
                call.respond(rules)
            }

            put {
                val scope = call.categoryScopeListId() ?: return@put
                val req = call.receive<UpsertCategoryRuleRequest>()
                when (val r = service.upsertRule(scope.value, req)) {
                    is ShoppingService.RuleResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    is ShoppingService.RuleResult.NotFound -> call.respond(HttpStatusCode.NotFound, r.error)
                    is ShoppingService.RuleResult.Ok -> {
                        broadcastRule("SHOPPING_CATEGORY_RULE_CHANGED", r.rule)
                        call.respond(r.rule)
                    }
                }
            }

            delete("/{name}") {
                val scope = call.categoryScopeListId() ?: return@delete
                when (val r = service.deleteRule(scope.value, call.parameters["name"])) {
                    is ShoppingService.RuleResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    is ShoppingService.RuleResult.NotFound -> call.respond(HttpStatusCode.NotFound, r.error)
                    is ShoppingService.RuleResult.Ok -> {
                        broadcastRule("SHOPPING_CATEGORY_RULE_CHANGED", r.rule)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }

        get("/suggestions") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 300
            val q = call.request.queryParameters["q"]?.let { GroceryCatalog.normalize(it) }?.takeIf { it.isNotBlank() }
            val scope = call.categoryScopeListId() ?: return@get
            call.respond(service.suggestions(scope.value, q, limit))
        }

        // Push several recipe ingredients onto a list at once (quantity-aware merge). See ShoppingService.
        post("/batch") {
            val username = call.username()
            val req = call.receive<BatchAddShoppingRequest>()

            val listId = req.listId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (req.listId != null && req.listId.isNotBlank() && listId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
                return@post
            }

            val lines = req.items.filter { it.name.isNotBlank() }
            if (lines.any { it.amount != null && it.amount < 0.0 }) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SHOPPING_ITEM", "amount must be >= 0"))
                return@post
            }

            val outcome = service.batchAdd(listId, lines, username)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                return@post
            }
            outcome.created.forEach { broadcastItem("SHOPPING_CREATED", it) }
            outcome.updated.forEach { broadcastItem("SHOPPING_UPDATED", it) }
            call.respond(
                BatchAddShoppingResponse(
                    added = outcome.created.size,
                    merged = outcome.updated.size,
                    skipped = outcome.skipped,
                    items = outcome.created + outcome.updated,
                ),
            )
        }

        get {
            call.respond(service.listItems())
        }

        post {
            val username = call.username()
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
            when (val r = service.createItem(req.name, listId, req.quantity, username)) {
                is ShoppingService.CreateItemResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                is ShoppingService.CreateItemResult.Ok -> {
                    broadcastItem("SHOPPING_CREATED", r.item)
                    call.respond(HttpStatusCode.Created, r.item)
                }
            }
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
            when (val r = service.updateItem(id, req, targetListId)) {
                ShoppingService.UpdateItemResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Shopping item not found"))
                is ShoppingService.UpdateItemResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                is ShoppingService.UpdateItemResult.Ok -> {
                    broadcastItem("SHOPPING_UPDATED", r.item)
                    call.respond(r.item)
                }
            }
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deletedItem = service.deleteItem(id)
            if (deletedItem == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Shopping item not found"))
                return@delete
            }
            broadcastItem("SHOPPING_DELETED", deletedItem)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    syncChannel(SHOPPING_WS_CHANNEL)
}

// ---- Per-list category scope (#412) -------------------------------------------------------------

/** An optional list scope for the category routes: null value = the shared household catalog. */
@JvmInline
private value class ListScope(val value: UUID?)

/**
 * Parse the optional `?listId=` category-scope query param (#412). Returns a [ListScope] (whose value
 * is the parsed UUID, or null when the param is absent = shared catalog), or null AFTER responding 400
 * on a malformed UUID — call as `val s = call.categoryScopeListId() ?: return@get`. Pure HTTP parsing
 * (no DB); the per-endpoint list-existence check lives in [ShoppingService].
 */
private suspend fun ApplicationCall.categoryScopeListId(): ListScope? {
    val raw = request.queryParameters["listId"]?.takeIf { it.isNotBlank() } ?: return ListScope(null)
    val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
        return null
    }
    return ListScope(uuid)
}
