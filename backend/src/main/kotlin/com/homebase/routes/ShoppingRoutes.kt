package com.homebase.routes

import com.homebase.db.ShoppingCategoriesTable
import com.homebase.db.ShoppingCategoryRulesTable
import com.homebase.db.ShoppingItemStatsTable
import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
import com.homebase.model.*
import com.homebase.shopping.GroceryCatalog
import com.homebase.shopping.ShoppingCatalog
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

private const val SHOPPING_WS_CHANNEL = "shopping"

fun Route.shoppingRoutes() {
    suspend fun broadcastItem(type: String, item: ShoppingItemDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingWsMessage(type, item)))

    suspend fun broadcastList(type: String, list: ShoppingListDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingListWsMessage(type, list)))

    suspend fun broadcastCategory(type: String, category: ShoppingCategoryDto?) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingCategoryWsMessage(type, category)))

    suspend fun broadcastRule(type: String, rule: ShoppingCategoryRuleDto?) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingCategoryRuleWsMessage(type, rule)))

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
                val username = call.username()
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
                        it[ownCategories] = req.ownCategories
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
                        // #412: flip the list between its own category set and the shared catalog. Custom
                        // category rows are kept when reverting (hidden), so re-enabling is lossless.
                        req.ownCategories?.let { v -> it[ownCategories] = v }
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
                    ShoppingCategoriesTable.deleteWhere { ShoppingCategoriesTable.listId eq id } // #412: own categories
                    ShoppingItemStatsTable.deleteWhere { ShoppingItemStatsTable.listScope eq id } // #501: own-scope usage stats (shared-scope rows stay)
                    ShoppingCategoryRulesTable.deleteWhere { ShoppingCategoryRulesTable.listScope eq id } // #501: own-scope rules (shared dictionary stays)
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

        // ---- Categories (editable catalog, #411): the household manages its own grocery categories.
        // Shared like the lists; builtins are editable AND deletable too (except OTHER, the fallback). ----
        route("/categories") {
            // GET /shopping/categories[?listId=L] (#412): the category set a list renders — its own set
            // (custom rows + the shared OTHER fallback) if it opted in, else the shared household catalog
            // (#411). No listId, or a shared/non-own list, → the shared catalog (Settings uses this).
            get {
                val listId = call.categoryScopeListId() ?: return@get
                val cats = transaction { categoriesForList(listId.value) }
                call.respond(cats)
            }

            // POST /shopping/categories[?listId=L] (#412): create a category. With listId it becomes that
            // list's own category (the list should already have own_categories = true); without, a shared one.
            post {
                val req = call.receive<CreateShoppingCategoryRequest>()
                val label = req.label.trim()
                val emoji = req.emoji.trim()
                if (label.isBlank() || emoji.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "label and emoji must not be blank"))
                    return@post
                }
                val scopeId = (call.categoryScopeListId() ?: return@post).value
                val created = transaction {
                    if (scopeId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq scopeId }.empty()) {
                        return@transaction null
                    }
                    val key = uniqueCategoryKey(label)
                    // default sort order is relative to the target scope (the list's own set — excluding
                    // the shared OTHER — or the shared catalog), so a first custom category lands before OTHER
                    val order = req.sortOrder
                        ?: ((ShoppingCategoriesTable.selectAll()
                            .where { if (scopeId != null) ShoppingCategoriesTable.listId eq scopeId else ShoppingCategoriesTable.listId.isNull() }
                            .maxOfOrNull { it[ShoppingCategoriesTable.sortOrder] } ?: -1) + 1)
                    ShoppingCategoriesTable.insert {
                        it[ShoppingCategoriesTable.key] = key
                        it[ShoppingCategoriesTable.label] = label
                        it[ShoppingCategoriesTable.emoji] = emoji
                        it[sortOrder] = order
                        it[isBuiltin] = false
                        it[ShoppingCategoriesTable.listId] = scopeId
                    }
                    ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.single().toCategoryDto()
                }
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
                val updated = transaction {
                    ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.singleOrNull()
                        ?: return@transaction null
                    ShoppingCategoriesTable.update({ ShoppingCategoriesTable.key eq key }) {
                        req.label?.let { v -> it[label] = v.trim() }
                        req.emoji?.let { v -> it[emoji] = v.trim() }
                        req.sortOrder?.let { v -> it[sortOrder] = v }
                    }
                    ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.single().toCategoryDto()
                }
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
                val deleted = transaction {
                    val existing = ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.singleOrNull()
                        ?: return@transaction null
                    // Reassign this category's items + remembered stats to OTHER so nothing dangles.
                    ShoppingItemsTable.update({ ShoppingItemsTable.category eq key }) { it[category] = GroceryCatalog.OTHER }
                    ShoppingItemStatsTable.update({ ShoppingItemStatsTable.category eq key }) { it[category] = GroceryCatalog.OTHER }
                    ShoppingCategoryRulesTable.update({ ShoppingCategoryRulesTable.category eq key }) { it[category] = GroceryCatalog.OTHER }
                    ShoppingCategoriesTable.deleteWhere { ShoppingCategoriesTable.key eq key }
                    existing.toCategoryDto()
                }
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Category not found")); return@delete
                }
                broadcastCategory("SHOPPING_CATEGORY_CHANGED", deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // ---- Category rules (editable auto-resolve dictionary, #411 PR B): name → category/icon that
        // new items auto-fill. PUT upserts by normalized name; DELETE removes. #501: scoped per list via
        // ?listId=L — an own-categories list has its own private dictionary, shared lists the household one. ----
        route("/category-rules") {
            get {
                val scope = call.categoryScopeListIdExisting() ?: return@get
                val rules = transaction {
                    val scopeId = ShoppingCatalog.statsScopeFor(scope.value)
                    ShoppingCategoryRulesTable.selectAll()
                        .where { ShoppingCategoryRulesTable.listScope eq scopeId }
                        .orderBy(ShoppingCategoryRulesTable.displayName to SortOrder.ASC)
                        .map { it.toCategoryRuleDto() }
                }
                call.respond(rules)
            }

            put {
                val scope = call.categoryScopeListIdExisting() ?: return@put
                val req = call.receive<UpsertCategoryRuleRequest>()
                val display = req.displayName.trim()
                val normalized = GroceryCatalog.normalize(display)
                if (display.isBlank() || normalized.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RULE", "displayName must not be blank")); return@put
                }
                val iconProvided = req.icon?.trim()?.takeIf { it.isNotBlank() }
                val saved = transaction {
                    val scopeId = ShoppingCatalog.statsScopeFor(scope.value)
                    // a rule may only target a category key that is live in its own scope (#412/#501):
                    // the shared dictionary → a shared key, an own list's dictionary → its own key.
                    if (req.category !in ShoppingCatalog.liveKeysForList(scope.value)) return@transaction null
                    val updated = ShoppingCategoryRulesTable.update({ (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq scopeId) }) {
                        it[displayName] = display
                        it[category] = req.category
                        // category-only edit keeps the existing icon; only overwrite when one is given
                        if (iconProvided != null) it[ShoppingCategoryRulesTable.icon] = iconProvided
                    }
                    if (updated == 0) {
                        ShoppingCategoryRulesTable.insert {
                            it[normalizedName] = normalized
                            it[listScope] = scopeId
                            it[displayName] = display
                            it[category] = req.category
                            it[ShoppingCategoryRulesTable.icon] = iconProvided ?: GroceryCatalog.DEFAULT_ICON
                        }
                    }
                    ShoppingCategoryRulesTable.selectAll().where { (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq scopeId) }.single().toCategoryRuleDto()
                }
                if (saved == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "category must be a known key")); return@put
                }
                broadcastRule("SHOPPING_CATEGORY_RULE_CHANGED", saved)
                call.respond(saved)
            }

            delete("/{name}") {
                val scope = call.categoryScopeListIdExisting() ?: return@delete
                val raw = call.parameters["name"]?.takeIf { it.isNotBlank() } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "name required")); return@delete
                }
                val normalized = GroceryCatalog.normalize(raw)
                val deleted = transaction {
                    val scopeId = ShoppingCatalog.statsScopeFor(scope.value)
                    val existing = ShoppingCategoryRulesTable.selectAll()
                        .where { (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq scopeId) }.singleOrNull()
                        ?: return@transaction null
                    ShoppingCategoryRulesTable.deleteWhere { (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq scopeId) }
                    existing.toCategoryRuleDto()
                }
                if (deleted == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Rule not found")); return@delete
                }
                broadcastRule("SHOPPING_CATEGORY_RULE_CHANGED", deleted)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // Autocomplete source (#389/#390): merges the scoped rules dictionary (count 0 baseline, useful
        // on day one) with the usage tally, ranked most-used first. #501: the dictionary + tally are per
        // list — a shared list sees the grocery names, an own-categories list only its own rule/used
        // names. Clients preload this once (per active list) and filter locally as the user types.
        get("/suggestions") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 300
            val q = call.request.queryParameters["q"]?.let { GroceryCatalog.normalize(it) }?.takeIf { it.isNotBlank() }
            // #412: scope the resolved categories/icons to the target list's set (unknown → OTHER).
            // #501: scope the usage tally per list too — an own-categories list shows only the names
            // actually used in it (its scoped stats), no grocery baseline; shared lists pool the global
            // dictionary + the shared-scope stats as before.
            val scope = call.categoryScopeListId() ?: return@get
            val suggestions = transaction {
                val statsScope = ShoppingCatalog.statsScopeFor(scope.value)
                val liveKeys = ShoppingCatalog.liveKeysForList(scope.value)
                val rules = ShoppingCatalog.loadRulesForList(scope.value) // scoped dictionary (#501)
                fun liveCat(cat: String) = if (cat in liveKeys) cat else GroceryCatalog.OTHER
                val merged = LinkedHashMap<String, ShoppingSuggestionDto>()
                // The scoped rules dictionary is the day-one baseline: the shared grocery names for a
                // shared list, the list's own rule names for an own list (empty until it defines any).
                rules.allEntries().forEach { e ->
                    merged[e.normalized] = ShoppingSuggestionDto(e.name, liveCat(e.category), e.icon, 0)
                }
                ShoppingItemStatsTable.selectAll().where { ShoppingItemStatsTable.listScope eq statsScope }.forEach { row ->
                    val key = row[ShoppingItemStatsTable.normalizedName]
                    val display = row[ShoppingItemStatsTable.displayName]
                    val resolved = ShoppingCatalog.resolve(display, rules, liveKeys)
                    val baseline = merged[key] // the catalog entry, if this is a known item
                    merged[key] = ShoppingSuggestionDto(
                        // prefer the catalog's canonical name so a lowercase add can't downgrade "Milch"
                        name = baseline?.name ?: display,
                        category = liveCat(row[ShoppingItemStatsTable.category] ?: baseline?.category ?: resolved.category),
                        icon = row[ShoppingItemStatsTable.icon] ?: baseline?.icon ?: resolved.icon,
                        count = row[ShoppingItemStatsTable.useCount],
                    )
                }
                merged.values.asSequence()
                    .filter { s -> q == null || GroceryCatalog.normalize(s.name).contains(q) }
                    .sortedWith(compareByDescending<ShoppingSuggestionDto> { it.count }.thenBy { it.name.lowercase() })
                    .take(limit)
                    .toList()
            }
            call.respond(suggestions)
        }

        // Push several recipe ingredients onto a list at once. Quantities are merged into an
        // existing item when name + unit match (e.g. "500 g Mehl" + "200 g Mehl" → "700 g Mehl");
        // otherwise the line is added on its own. Amounts arrive already scaled by the client.
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

            val created = mutableListOf<ShoppingItemDto>()
            val updated = mutableListOf<ShoppingItemDto>()
            val usedNames = mutableListOf<String>() // bare names to tally after the tx (best-effort)
            var skipped = 0

            val listExists = transaction {
                if (listId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }.empty()) {
                    return@transaction false
                }
                val liveKeys = ShoppingCatalog.liveKeysForList(listId) // categorize against the target list's set (#411/#412)
                val statsScope = ShoppingCatalog.statsScopeFor(listId) // remembered corrections per list (#501)
                val rules = ShoppingCatalog.loadRulesForList(listId) // auto-resolve rules per list (#501)
                // Working snapshot of the target bucket (a real list, or the null/unfiled bucket).
                val working = ShoppingItemsTable.selectAll()
                    .where { if (listId != null) ShoppingItemsTable.listId eq listId else ShoppingItemsTable.listId.isNull() }
                    .map { WorkingItem(it[ShoppingItemsTable.id], it[ShoppingItemsTable.name]) }
                    .toMutableList()

                for (line in lines) {
                    val name = line.name.trim()
                    val unit = line.unit?.trim()?.takeIf { it.isNotBlank() }
                    val amount = line.amount
                    val display = formatLine(amount, unit, name)

                    // 1. Mergeable into an existing numeric line with the same name + unit?
                    val target = if (amount != null) working.firstOrNull { w ->
                        val p = parseQty(w.name)
                        p.amount != null && p.name.equals(name, ignoreCase = true) && unitsMatch(p.unit, unit)
                    } else null

                    if (target != null) {
                        val p = parseQty(target.name)
                        val mergedName = formatLine(p.amount!! + amount!!, p.unit ?: unit, p.name)
                        ShoppingItemsTable.update({ ShoppingItemsTable.id eq target.id }) { it[ShoppingItemsTable.name] = mergedName }
                        target.name = mergedName
                        usedNames += name // count the re-add toward "most used"
                        updated += ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq target.id }.single().toDto()
                        continue
                    }

                    // 2. Exact duplicate (same label already present) → leave it be.
                    if (working.any { it.name.equals(display, ignoreCase = true) }) {
                        skipped++
                        continue
                    }

                    // 3. Otherwise add a new item.
                    val id = UUID.randomUUID()
                    val (resolvedCategory, resolvedIcon) = resolveForItem(name, rules, liveKeys, statsScope)
                    ShoppingItemsTable.insert {
                        it[ShoppingItemsTable.id] = id
                        it[ShoppingItemsTable.name] = display
                        it[ShoppingItemsTable.listId] = listId
                        it[checked] = false
                        it[createdBy] = username
                        it[createdAt] = Instant.now()
                        it[ShoppingItemsTable.category] = resolvedCategory
                        it[ShoppingItemsTable.icon] = resolvedIcon
                    }
                    usedNames += name
                    working += WorkingItem(id, display)
                    created += ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto()
                }
                true
            }

            if (!listExists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                return@post
            }

            recordUsages(usedNames, listId) // best-effort tally in a separate tx (never rolls back the batch)
            created.forEach { broadcastItem("SHOPPING_CREATED", it) }
            updated.forEach { broadcastItem("SHOPPING_UPDATED", it) }
            call.respond(
                BatchAddShoppingResponse(
                    added = created.size,
                    merged = updated.size,
                    skipped = skipped,
                    items = created + updated,
                )
            )
        }

        get {
            val items = transaction {
                ShoppingItemsTable.selectAll().map { it.toDto() }
            }
            call.respond(items)
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

            val item = transaction {
                if (listId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }.empty()) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }
                val id = UUID.randomUUID()
                val (resolvedCategory, resolvedIcon) = resolveForItem(req.name, ShoppingCatalog.loadRulesForList(listId), ShoppingCatalog.liveKeysForList(listId), ShoppingCatalog.statsScopeFor(listId)) // #412/#501: the item's list scope
                ShoppingItemsTable.insert {
                    it[ShoppingItemsTable.id] = id
                    it[name] = req.name
                    it[ShoppingItemsTable.listId] = listId
                    it[checked] = false
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                    it[ShoppingItemsTable.category] = resolvedCategory
                    it[ShoppingItemsTable.icon] = resolvedIcon
                    it[quantity] = req.quantity?.takeIf { q -> q.isNotBlank() }?.trim()
                }
                ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto()
            }

            if (item is ErrorResponse) {
                call.respond(HttpStatusCode.BadRequest, item)
                return@post
            }
            recordUsages(listOf(req.name), listId) // best-effort tally in a separate tx (never rolls back the item)
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
            // Manual category/icon override (#389/#390): blank = unchanged; a category must be a known key.
            val newCategory = req.category?.takeIf { it.isNotBlank() }
            // Icon override (#389/#390/#508): null = unchanged, "" = clear the override (fall back to
            // name/category auto-resolution), else the chosen svg-basename.
            val clearIcon = req.icon != null && req.icon.isBlank()
            val newIcon = req.icon?.takeIf { it.isNotBlank() }

            val item = transaction {
                val existing = ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                if (targetListId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq targetListId }.empty()) {
                    return@transaction ErrorResponse("NOT_FOUND", "List not found")
                }
                // category override must be a live catalog key (#411), validated against the item's
                // DESTINATION list's set (#412): the list it's being moved to, else its current list.
                val finalListId = if (req.listId != null) targetListId else existing[ShoppingItemsTable.listId]
                if (newCategory != null && newCategory !in ShoppingCatalog.liveKeysForList(finalListId)) {
                    return@transaction ErrorResponse("INVALID_CATEGORY", "category must be a known key")
                }

                ShoppingItemsTable.update({ ShoppingItemsTable.id eq id }) {
                    req.name?.let { v -> it[name] = v }
                    req.listId?.let { _ -> it[listId] = targetListId }
                    req.checked?.let { v ->
                        it[checked] = v
                        it[checkedAt] = if (v) Instant.now() else null
                    }
                    newCategory?.let { v -> it[category] = v }
                    when {
                        clearIcon -> it[icon] = null
                        newIcon != null -> it[icon] = newIcon
                    }
                    // Free-text details (#445): null = unchanged, blank = clear, else trimmed value.
                    req.quantity?.let { v -> it[quantity] = v.trim().ifBlank { null } }
                    req.note?.let { v -> it[note] = v.trim().ifBlank { null } }
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

            // Remember the correction (best-effort, separate tx) so future adds of this name pick it up.
            // Scoped to the item's (destination) list so two own lists can remember it differently (#501).
            if (newCategory != null || newIcon != null) {
                val dto = item as ShoppingItemDto
                rememberStatsPreference(dto.name, newCategory, newIcon, dto.listId?.let { runCatching { UUID.fromString(it) }.getOrNull() })
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
    ownCategories = this[ShoppingListsTable.ownCategories],
)

private fun ResultRow.toDto() = ShoppingItemDto(
    id = this[ShoppingItemsTable.id].toString(),
    name = this[ShoppingItemsTable.name],
    listId = this[ShoppingItemsTable.listId]?.toString(),
    checked = this[ShoppingItemsTable.checked],
    createdBy = this[ShoppingItemsTable.createdBy],
    createdAt = this[ShoppingItemsTable.createdAt].toString(),
    checkedAt = this[ShoppingItemsTable.checkedAt]?.toString(),
    category = this[ShoppingItemsTable.category],
    icon = this[ShoppingItemsTable.icon],
    quantity = this[ShoppingItemsTable.quantity],
    note = this[ShoppingItemsTable.note],
)

private fun ResultRow.toCategoryDto() = ShoppingCategoryDto(
    key = this[ShoppingCategoriesTable.key],
    label = this[ShoppingCategoriesTable.label],
    emoji = this[ShoppingCategoriesTable.emoji],
    sortOrder = this[ShoppingCategoriesTable.sortOrder],
    isBuiltin = this[ShoppingCategoriesTable.isBuiltin],
    listId = this[ShoppingCategoriesTable.listId]?.toString(),
)

private fun ResultRow.toCategoryRuleDto() = ShoppingCategoryRuleDto(
    normalizedName = this[ShoppingCategoryRulesTable.normalizedName],
    displayName = this[ShoppingCategoryRulesTable.displayName],
    category = this[ShoppingCategoryRulesTable.category],
    icon = this[ShoppingCategoryRulesTable.icon],
    // #501: surface the owning list (null for the shared household dictionary sentinel).
    listId = this[ShoppingCategoryRulesTable.listScope].takeIf { it != ShoppingCatalog.SHARED_STATS_SCOPE }?.toString(),
)

// ---- Per-list category scope (#412) -------------------------------------------------------------

/** An optional list scope for the category routes: null value = the shared household catalog. */
@JvmInline
private value class ListScope(val value: UUID?)

/**
 * Parse the optional `?listId=` category-scope query param (#412). Returns a [ListScope] (whose value
 * is the parsed UUID, or null when the param is absent = shared catalog), or null AFTER responding 400
 * on a malformed UUID — call as `val s = call.categoryScopeListId() ?: return@get`.
 */
private suspend fun ApplicationCall.categoryScopeListId(): ListScope? {
    val raw = request.queryParameters["listId"]?.takeIf { it.isNotBlank() } ?: return ListScope(null)
    val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "listId must be a valid UUID"))
        return null
    }
    return ListScope(uuid)
}

/**
 * Like [categoryScopeListId] but also 404s when a well-formed listId names a non-existent list (#538),
 * instead of silently falling back to the SHARED dictionary. Mirrors the item/resolve sites, which
 * already 404 on an unknown listId — call as `val s = call.categoryScopeListIdExisting() ?: return@get`.
 */
private suspend fun ApplicationCall.categoryScopeListIdExisting(): ListScope? {
    val scope = categoryScopeListId() ?: return null
    val id = scope.value ?: return scope // no listId → shared dictionary, nothing to verify
    val exists = transaction { !ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.empty() }
    if (!exists) {
        respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
        return null
    }
    return scope
}

/**
 * The ordered category DTOs a list renders (#412). Mirrors [ShoppingCatalog.liveKeysForList]'s scope
 * rule: a list that opted into its own set → its custom rows + the shared OTHER fallback; every other
 * list (and no listId) → the shared household catalog. Call inside a transaction.
 */
private fun categoriesForList(listId: UUID?): List<ShoppingCategoryDto> {
    val own = listId != null &&
        ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }
            .firstOrNull()?.get(ShoppingListsTable.ownCategories) == true
    val query = if (own) {
        ShoppingCategoriesTable.selectAll()
            .where { (ShoppingCategoriesTable.listId eq listId) or (ShoppingCategoriesTable.key eq GroceryCatalog.OTHER) }
    } else {
        ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.listId.isNull() }
    }
    return query
        .orderBy(ShoppingCategoriesTable.sortOrder to SortOrder.ASC, ShoppingCategoriesTable.key to SortOrder.ASC)
        .map { it.toCategoryDto() }
}

/**
 * Generate a stable, unique category key from a label: uppercase ASCII slug (non-alnum → "_"), capped
 * at 40 chars, numeric suffix on collision. Call inside a transaction (it reads the existing keys).
 * The stored key is the immutable id on items; custom labels rarely collide with the builtin words.
 */
private fun uniqueCategoryKey(label: String): String {
    val base = label.uppercase()
        .map { if (it in 'A'..'Z' || it in '0'..'9') it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(40)
        .ifBlank { "CAT" }
    val existing = ShoppingCategoriesTable.selectAll().mapTo(HashSet()) { it[ShoppingCategoriesTable.key] }
    if (base !in existing) return base
    var i = 2
    while (true) {
        val candidate = base.take(36) + "_" + i
        if (candidate !in existing) return candidate
        i++
    }
}

// ---- Categorization + usage stats (#389/#390) --------------------------------------------------
//
// Stats writes are intentionally split from the item write: resolveForItem() only READS (safe inside
// the item transaction), while recordUsages()/rememberStatsPreference() WRITE in their own, best-effort
// transactions. Under the prod REPEATABLE_READ isolation a concurrent stats write could raise a
// serialization/duplicate-key error; keeping it in a separate try/catch'd tx ensures that can never
// roll back the user's actual item create/update. Mirrors the update-then-insert idiom of upsertPref().

/**
 * The category + icon to show on a freshly added item: a remembered override (stats row in this list's
 * [scope], #501) wins over the catalog, which falls back to OTHER + cart. Read-only — safe inside any
 * transaction.
 */
private fun resolveForItem(rawName: String, rules: ShoppingCatalog.RuleSet, liveKeys: Set<String>, scope: UUID): Pair<String, String> {
    val resolved = ShoppingCatalog.resolve(rawName, rules, liveKeys)
    val key = GroceryCatalog.normalize(rawName)
    if (key.isBlank()) return resolved.category to resolved.icon
    val existing = ShoppingItemStatsTable.selectAll()
        .where { (ShoppingItemStatsTable.normalizedName eq key) and (ShoppingItemStatsTable.listScope eq scope) }.singleOrNull()
    // a remembered override pointing at a since-deleted category falls back to the resolved one (#411)
    val statsCategory = existing?.get(ShoppingItemStatsTable.category)?.takeIf { it in liveKeys }
    return (statsCategory ?: resolved.category) to
        (existing?.get(ShoppingItemStatsTable.icon) ?: resolved.icon)
}

/**
 * Bump the autocomplete usage tally for each added name, in [listId]'s stats scope (#501). Own
 * transaction, failures swallowed (the tally is non-critical and must never roll back the caller's
 * item write). New row seeded from the catalog; existing row incremented. Call AFTER the item
 * transaction has committed.
 */
private fun recordUsages(rawNames: List<String>, listId: UUID?) {
    val entries = rawNames.mapNotNull { raw ->
        val key = GroceryCatalog.normalize(raw)
        if (key.isBlank()) null else key to raw.trim()
    }
    if (entries.isEmpty()) return
    runCatching {
        transaction {
            val scope = ShoppingCatalog.statsScopeFor(listId)
            val liveKeys = ShoppingCatalog.liveKeysForList(listId)
            val rules = ShoppingCatalog.loadRulesForList(listId)
            for ((key, display) in entries) {
                val existing = ShoppingItemStatsTable.selectAll()
                    .where { (ShoppingItemStatsTable.normalizedName eq key) and (ShoppingItemStatsTable.listScope eq scope) }.singleOrNull()
                if (existing == null) {
                    val resolved = ShoppingCatalog.resolve(display, rules, liveKeys)
                    ShoppingItemStatsTable.insert {
                        it[normalizedName] = key
                        it[listScope] = scope
                        it[displayName] = display
                        it[category] = resolved.category
                        it[icon] = resolved.icon
                        it[useCount] = 1
                        it[lastUsedAt] = Instant.now()
                    }
                } else {
                    ShoppingItemStatsTable.update({ (ShoppingItemStatsTable.normalizedName eq key) and (ShoppingItemStatsTable.listScope eq scope) }) {
                        it[useCount] = existing[ShoppingItemStatsTable.useCount] + 1
                        it[lastUsedAt] = Instant.now()
                        it[displayName] = display
                    }
                }
            }
        }
    }
}

/**
 * Remember a manual category/icon override for [rawName] in [listId]'s stats scope (#501) so future
 * adds of that name in the same scope pick it up. Own transaction, failures swallowed (same rationale
 * as [recordUsages]); does not count a use.
 */
private fun rememberStatsPreference(rawName: String, categoryOverride: String?, iconOverride: String?, listId: UUID?) {
    if (categoryOverride == null && iconOverride == null) return
    val key = GroceryCatalog.normalize(rawName)
    if (key.isBlank()) return
    runCatching {
        transaction {
            val scope = ShoppingCatalog.statsScopeFor(listId)
            val updated = ShoppingItemStatsTable.update({ (ShoppingItemStatsTable.normalizedName eq key) and (ShoppingItemStatsTable.listScope eq scope) }) {
                categoryOverride?.let { v -> it[category] = v }
                iconOverride?.let { v -> it[icon] = v }
            }
            if (updated == 0) {
                ShoppingItemStatsTable.insert {
                    it[normalizedName] = key
                    it[listScope] = scope
                    it[displayName] = rawName.trim()
                    it[category] = categoryOverride
                    it[icon] = iconOverride
                    it[useCount] = 0
                    it[lastUsedAt] = Instant.now()
                }
            }
        }
    }
}

// ---- Batch add: quantity-aware merging of "200 g Mehl" style labels ----------------------

/** Mutable view of a list item used while a batch add reconciles against the existing entries. */
private class WorkingItem(val id: UUID, var name: String)

/** Short units recognised when parsing a "200 g Mehl" shopping label back into parts. */
//
// Backend-intern: nur die Shopping-Merge-Heuristik (parseQty) nutzt diese Liste. Früher war sie
// bewusst mit dem Android-Rezept-Freitext-Parser gespiegelt; der ist mit #28 entfallen (Android
// nutzt jetzt strukturierte Zutaten-Zeilen, Web hatte nie einen Freitext-Parser). Siehe Issue #103.
private val KNOWN_UNITS = setOf(
    "g", "kg", "mg", "ml", "l", "el", "tl", "stk", "stück", "prise",
    "bund", "dose", "pkg", "pck", "tasse", "cup", "msp",
)

private data class ParsedQty(val amount: Double?, val unit: String?, val name: String)

/**
 * Split a label like "200 g Mehl" into amount / unit / name. A leading number (comma decimals
 * allowed) is the amount; a following token that is a known unit (KNOWN_UNITS) is the unit; the rest
 * is the name. Without a leading number the whole string is the name (amount/unit null).
 *
 * Only KNOWN_UNITS count as a unit — earlier we also treated any short letter-only token as one,
 * which swallowed the first word of a multi-word name ("2 rote Paprika" → unit="rote") and broke the
 * merge for such ingredients. Our own labels always carry structured units, so the whitelist is
 * enough. See issue #47.
 *
 * Backend-intern (nur Shopping-Merge): früher mit Androids `parseIngredientLine` gespiegelt, das
 * mit #28 entfiel — kein Client teilt diese Heuristik mehr. Siehe Issue #103.
 */
private fun parseQty(line: String): ParsedQty {
    val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return ParsedQty(null, null, line.trim())
    val amount = tokens[0].replace(',', '.').toDoubleOrNull()
        ?: return ParsedQty(null, null, line.trim())
    var idx = 1
    var unit: String? = null
    if (idx < tokens.size) {
        val candidate = tokens[idx]
        if (candidate.lowercase() in KNOWN_UNITS && idx < tokens.size - 1) { unit = candidate; idx++ }
    }
    val name = tokens.drop(idx).joinToString(" ")
    return if (name.isBlank()) ParsedQty(null, null, line.trim()) else ParsedQty(amount, unit, name)
}

/** Units match case-insensitively; a missing unit is treated as blank, so null matches null. */
private fun unitsMatch(a: String?, b: String?): Boolean = (a ?: "").lowercase() == (b ?: "").lowercase()

/** "1,5" → drops a trailing ".0", keeps up to 3 decimals (matches the recipe scaling on the server). */
private fun fmtAmount(value: Double): String {
    val r = Math.round(value * 1000.0) / 1000.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
}

/** Build a "200 g Mehl" label, omitting an absent amount and/or unit. */
private fun formatLine(amount: Double?, unit: String?, name: String): String =
    listOfNotNull(amount?.let { fmtAmount(it) }, unit?.takeIf { it.isNotBlank() }, name)
        .joinToString(" ").trim()
