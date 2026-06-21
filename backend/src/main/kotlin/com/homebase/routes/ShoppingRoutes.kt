package com.homebase.routes

import com.homebase.db.ShoppingItemStatsTable
import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
import com.homebase.model.*
import com.homebase.shopping.GroceryCatalog
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
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

private const val SHOPPING_WS_CHANNEL = "shopping"

fun Route.shoppingRoutes() {
    suspend fun broadcastItem(type: String, item: ShoppingItemDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingWsMessage(type, item)))

    suspend fun broadcastList(type: String, list: ShoppingListDto) =
        WsSessionManager.broadcast(SHOPPING_WS_CHANNEL, appJson.encodeToString(ShoppingListWsMessage(type, list)))

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

        // Autocomplete source (#389/#390): the known catalog (count 0 baseline, useful on day one)
        // merged with the household's real usage tally, ranked most-used first. Clients preload this
        // once and filter locally as the user types.
        get("/suggestions") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 300
            val q = call.request.queryParameters["q"]?.let { GroceryCatalog.normalize(it) }?.takeIf { it.isNotBlank() }
            val suggestions = transaction {
                val merged = LinkedHashMap<String, ShoppingSuggestionDto>()
                GroceryCatalog.allEntries().forEach { e ->
                    merged[e.normalized] = ShoppingSuggestionDto(e.name, e.category, e.icon, 0)
                }
                ShoppingItemStatsTable.selectAll().forEach { row ->
                    val name = row[ShoppingItemStatsTable.displayName]
                    val resolved = GroceryCatalog.resolve(name)
                    merged[row[ShoppingItemStatsTable.normalizedName]] = ShoppingSuggestionDto(
                        name = name,
                        category = row[ShoppingItemStatsTable.category] ?: resolved.category,
                        icon = row[ShoppingItemStatsTable.icon] ?: resolved.icon,
                        count = row[ShoppingItemStatsTable.useCount],
                    )
                }
                merged.values.asSequence()
                    .filter { s -> q == null || GroceryCatalog.normalize(s.name).let { it.startsWith(q) || it.contains(q) } }
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
            var skipped = 0

            val listExists = transaction {
                if (listId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }.empty()) {
                    return@transaction false
                }
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
                        resolveAndRecord(name) // count the re-add toward "most used"
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
                    val (resolvedCategory, resolvedIcon) = resolveAndRecord(name)
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
                    working += WorkingItem(id, display)
                    created += ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto()
                }
                true
            }

            if (!listExists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                return@post
            }

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
                val (resolvedCategory, resolvedIcon) = resolveAndRecord(req.name)
                ShoppingItemsTable.insert {
                    it[ShoppingItemsTable.id] = id
                    it[name] = req.name
                    it[ShoppingItemsTable.listId] = listId
                    it[checked] = false
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                    it[ShoppingItemsTable.category] = resolvedCategory
                    it[ShoppingItemsTable.icon] = resolvedIcon
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
            // Manual category/icon override (#389/#390): blank = unchanged; a category must be a known key.
            val newCategory = req.category?.takeIf { it.isNotBlank() }
            val newIcon = req.icon?.takeIf { it.isNotBlank() }
            if (newCategory != null && !GroceryCatalog.isValidCategory(newCategory)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "category must be a known key"))
                return@put
            }

            val item = transaction {
                val existingRow = ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.singleOrNull()
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
                    newCategory?.let { v -> it[category] = v }
                    newIcon?.let { v -> it[icon] = v }
                }
                // Remember the correction so future adds of this name pick it up.
                if (newCategory != null || newIcon != null) {
                    rememberStatsPreference(req.name ?: existingRow[ShoppingItemsTable.name], newCategory, newIcon)
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
    checkedAt = this[ShoppingItemsTable.checkedAt]?.toString(),
    category = this[ShoppingItemsTable.category],
    icon = this[ShoppingItemsTable.icon],
)

// ---- Categorization + usage stats (#389/#390) --------------------------------------------------

/**
 * Resolve an item's category + icon and record one "use" in shopping_item_stats. Must run inside a
 * transaction. A remembered household override (stats row) wins over the catalog, which falls back
 * to OTHER + cart. A fresh stats row is seeded with the resolved values; an existing row's count is
 * bumped, last_used stamped, and any missing category/icon backfilled. Returns (category, icon) to
 * store on the item row.
 */
private fun resolveAndRecord(rawName: String): Pair<String, String> {
    val resolved = GroceryCatalog.resolve(rawName)
    val key = GroceryCatalog.normalize(rawName)
    if (key.isBlank()) return resolved.category to resolved.icon
    val existing = ShoppingItemStatsTable.selectAll()
        .where { ShoppingItemStatsTable.normalizedName eq key }.singleOrNull()
    val effectiveCategory = existing?.get(ShoppingItemStatsTable.category) ?: resolved.category
    val effectiveIcon = existing?.get(ShoppingItemStatsTable.icon) ?: resolved.icon
    if (existing == null) {
        ShoppingItemStatsTable.insert {
            it[normalizedName] = key
            it[displayName] = rawName.trim()
            it[category] = effectiveCategory
            it[icon] = effectiveIcon
            it[useCount] = 1
            it[lastUsedAt] = Instant.now()
        }
    } else {
        ShoppingItemStatsTable.update({ ShoppingItemStatsTable.normalizedName eq key }) {
            it[useCount] = existing[ShoppingItemStatsTable.useCount] + 1
            it[lastUsedAt] = Instant.now()
            it[displayName] = rawName.trim()
            if (existing[ShoppingItemStatsTable.category] == null) it[category] = effectiveCategory
            if (existing[ShoppingItemStatsTable.icon] == null) it[icon] = effectiveIcon
        }
    }
    return effectiveCategory to effectiveIcon
}

/**
 * Remember a manual category/icon override for [rawName] in shopping_item_stats without counting a
 * use, so future adds of that name pick it up. Must run inside a transaction.
 */
private fun rememberStatsPreference(rawName: String, categoryOverride: String?, iconOverride: String?) {
    if (categoryOverride == null && iconOverride == null) return
    val key = GroceryCatalog.normalize(rawName)
    if (key.isBlank()) return
    val existing = ShoppingItemStatsTable.selectAll()
        .where { ShoppingItemStatsTable.normalizedName eq key }.singleOrNull()
    if (existing == null) {
        ShoppingItemStatsTable.insert {
            it[normalizedName] = key
            it[displayName] = rawName.trim()
            it[category] = categoryOverride
            it[icon] = iconOverride
            it[useCount] = 0
            it[lastUsedAt] = Instant.now()
        }
    } else {
        ShoppingItemStatsTable.update({ ShoppingItemStatsTable.normalizedName eq key }) {
            categoryOverride?.let { v -> it[category] = v }
            iconOverride?.let { v -> it[icon] = v }
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
