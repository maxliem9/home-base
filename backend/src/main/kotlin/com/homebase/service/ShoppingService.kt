package com.homebase.service

import com.homebase.db.ShoppingCategoriesTable
import com.homebase.db.ShoppingCategoryRulesTable
import com.homebase.db.ShoppingItemStatsTable
import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
import com.homebase.db.dbQuery
import com.homebase.model.*
import com.homebase.shopping.GroceryCatalog
import com.homebase.shopping.ShoppingCatalog
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

/**
 * Owns the shopping domain's persistence and business rules (issue #562, following the TodoService
 * pattern of #546): lists, the editable category catalog (#411/#412), the per-list auto-resolve rule
 * dictionary (#411/#501), autocomplete suggestions, quantity-aware batch add and item CRUD with the
 * usage-stats side-tallies. Methods are `suspend` and use the non-blocking `dbQuery {}` wrapper (#549).
 *
 * The route keeps the HTTP-shape concerns (query/path parsing, blank/UUID validation) and the
 * post-commit broadcasts (`broadcastSync`); every DB touch lives here. In-transaction faults are
 * returned as typed results (`Invalid` → 400, `NotFound` → 404) so the route only maps a status.
 */
class ShoppingService {

    // ---- Lists -----------------------------------------------------------

    suspend fun listLists(): List<ShoppingListDto> = dbQuery {
        ShoppingListsTable.selectAll()
            .orderBy(ShoppingListsTable.createdAt to SortOrder.ASC)
            .map { it.toListDto() }
    }

    /** Caller has already ensured [name] is non-blank. */
    suspend fun createList(name: String, ownCategories: Boolean, username: String): ShoppingListDto = dbQuery {
        val id = UUID.randomUUID()
        ShoppingListsTable.insert {
            it[ShoppingListsTable.id] = id
            it[ShoppingListsTable.name] = name.trim()
            it[createdBy] = username
            it[createdAt] = Instant.now()
            it[ShoppingListsTable.ownCategories] = ownCategories
        }
        ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.single().toListDto()
    }

    /** Returns null when the list does not exist (→ 404). */
    suspend fun updateList(id: UUID, req: UpdateShoppingListRequest): ShoppingListDto? = dbQuery {
        ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.singleOrNull()
            ?: return@dbQuery null
        ShoppingListsTable.update({ ShoppingListsTable.id eq id }) {
            req.name?.let { v -> it[name] = v.trim() }
            // #412: flip the list between its own category set and the shared catalog. Custom
            // category rows are kept when reverting (hidden), so re-enabling is lossless.
            req.ownCategories?.let { v -> it[ownCategories] = v }
        }
        ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.single().toListDto()
    }

    /** Returns null when the list does not exist (→ 404). */
    suspend fun deleteList(id: UUID): ShoppingListDto? = dbQuery {
        val existing = ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq id }.singleOrNull()
            ?: return@dbQuery null
        // explicit cascade (mirrors ON DELETE CASCADE for the H2 test DB)
        ShoppingItemsTable.deleteWhere { ShoppingItemsTable.listId eq id }
        ShoppingCategoriesTable.deleteWhere { ShoppingCategoriesTable.listId eq id } // #412: own categories
        ShoppingItemStatsTable.deleteWhere { ShoppingItemStatsTable.listScope eq id } // #501: own-scope usage stats (shared-scope rows stay)
        ShoppingCategoryRulesTable.deleteWhere { ShoppingCategoryRulesTable.listScope eq id } // #501: own-scope rules (shared dictionary stays)
        ShoppingListsTable.deleteWhere { ShoppingListsTable.id eq id }
        existing.toListDto()
    }

    // ---- Categories (#411/#412) -----------------------------------------

    suspend fun categories(listId: UUID?): List<ShoppingCategoryDto> = dbQuery { categoriesForList(listId) }

    /** Caller has ensured label/emoji are non-blank. Returns null when [scopeId] names a missing list (→ 404). */
    suspend fun createCategory(scopeId: UUID?, label: String, emoji: String, sortOrder: Int?): ShoppingCategoryDto? = dbQuery {
        if (scopeId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq scopeId }.empty()) {
            return@dbQuery null
        }
        val key = uniqueCategoryKey(label)
        // default sort order is relative to the target scope (the list's own set — excluding the shared
        // OTHER — or the shared catalog), so a first custom category lands before OTHER
        val order = sortOrder
            ?: ((ShoppingCategoriesTable.selectAll()
                .where { if (scopeId != null) ShoppingCategoriesTable.listId eq scopeId else ShoppingCategoriesTable.listId.isNull() }
                .maxOfOrNull { it[ShoppingCategoriesTable.sortOrder] } ?: -1) + 1)
        ShoppingCategoriesTable.insert {
            it[ShoppingCategoriesTable.key] = key
            it[ShoppingCategoriesTable.label] = label
            it[ShoppingCategoriesTable.emoji] = emoji
            it[ShoppingCategoriesTable.sortOrder] = order
            it[isBuiltin] = false
            it[ShoppingCategoriesTable.listId] = scopeId
        }
        ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.single().toCategoryDto()
    }

    /** Caller has ensured any provided label/emoji are non-blank. Returns null when the key is unknown (→ 404). */
    suspend fun updateCategory(key: String, req: UpdateShoppingCategoryRequest): ShoppingCategoryDto? = dbQuery {
        ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.singleOrNull()
            ?: return@dbQuery null
        ShoppingCategoriesTable.update({ ShoppingCategoriesTable.key eq key }) {
            req.label?.let { v -> it[label] = v.trim() }
            req.emoji?.let { v -> it[emoji] = v.trim() }
            req.sortOrder?.let { v -> it[sortOrder] = v }
        }
        ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.single().toCategoryDto()
    }

    /** Caller has rejected the protected OTHER key already. Returns null when the key is unknown (→ 404). */
    suspend fun deleteCategory(key: String): ShoppingCategoryDto? = dbQuery {
        val existing = ShoppingCategoriesTable.selectAll().where { ShoppingCategoriesTable.key eq key }.singleOrNull()
            ?: return@dbQuery null
        // Reassign this category's items + remembered stats to OTHER so nothing dangles.
        ShoppingItemsTable.update({ ShoppingItemsTable.category eq key }) { it[category] = GroceryCatalog.OTHER }
        ShoppingItemStatsTable.update({ ShoppingItemStatsTable.category eq key }) { it[category] = GroceryCatalog.OTHER }
        ShoppingCategoryRulesTable.update({ ShoppingCategoryRulesTable.category eq key }) { it[category] = GroceryCatalog.OTHER }
        ShoppingCategoriesTable.deleteWhere { ShoppingCategoriesTable.key eq key }
        existing.toCategoryDto()
    }

    // ---- Category rules (#411 PR B / #501) ------------------------------

    /** Returns null when [scopeId] names a missing list (→ 404, "List not found"). */
    suspend fun rules(scopeId: UUID?): List<ShoppingCategoryRuleDto>? = dbQuery {
        if (!scopeListExists(scopeId)) return@dbQuery null
        val statsScope = ShoppingCatalog.statsScopeFor(scopeId)
        ShoppingCategoryRulesTable.selectAll()
            .where { ShoppingCategoryRulesTable.listScope eq statsScope }
            .orderBy(ShoppingCategoryRulesTable.displayName to SortOrder.ASC)
            .map { it.toCategoryRuleDto() }
    }

    sealed interface RuleResult {
        data class Ok(val rule: ShoppingCategoryRuleDto) : RuleResult
        data class Invalid(val error: ErrorResponse) : RuleResult
        data class NotFound(val error: ErrorResponse) : RuleResult
    }

    suspend fun upsertRule(scopeId: UUID?, req: UpsertCategoryRuleRequest): RuleResult = dbQuery {
        if (!scopeListExists(scopeId)) return@dbQuery RuleResult.NotFound(ErrorResponse("NOT_FOUND", "List not found"))
        val display = req.displayName.trim()
        val normalized = GroceryCatalog.normalize(display)
        if (display.isBlank() || normalized.isBlank()) {
            return@dbQuery RuleResult.Invalid(ErrorResponse("INVALID_RULE", "displayName must not be blank"))
        }
        val iconProvided = req.icon?.trim()?.takeIf { it.isNotBlank() }
        val statsScope = ShoppingCatalog.statsScopeFor(scopeId)
        // a rule may only target a category key that is live in its own scope (#412/#501):
        // the shared dictionary → a shared key, an own list's dictionary → its own key.
        if (req.category !in ShoppingCatalog.liveKeysForList(scopeId)) {
            return@dbQuery RuleResult.Invalid(ErrorResponse("INVALID_CATEGORY", "category must be a known key"))
        }
        val updated = ShoppingCategoryRulesTable.update({ (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq statsScope) }) {
            it[displayName] = display
            it[category] = req.category
            // category-only edit keeps the existing icon; only overwrite when one is given
            if (iconProvided != null) it[ShoppingCategoryRulesTable.icon] = iconProvided
        }
        if (updated == 0) {
            ShoppingCategoryRulesTable.insert {
                it[normalizedName] = normalized
                it[listScope] = statsScope
                it[displayName] = display
                it[category] = req.category
                it[ShoppingCategoryRulesTable.icon] = iconProvided ?: GroceryCatalog.DEFAULT_ICON
            }
        }
        RuleResult.Ok(
            ShoppingCategoryRulesTable.selectAll().where { (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq statsScope) }.single().toCategoryRuleDto(),
        )
    }

    suspend fun deleteRule(scopeId: UUID?, rawName: String?): RuleResult = dbQuery {
        if (!scopeListExists(scopeId)) return@dbQuery RuleResult.NotFound(ErrorResponse("NOT_FOUND", "List not found"))
        if (rawName.isNullOrBlank()) return@dbQuery RuleResult.Invalid(ErrorResponse("INVALID_ID", "name required"))
        val normalized = GroceryCatalog.normalize(rawName)
        val statsScope = ShoppingCatalog.statsScopeFor(scopeId)
        val existing = ShoppingCategoryRulesTable.selectAll()
            .where { (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq statsScope) }.singleOrNull()
            ?: return@dbQuery RuleResult.NotFound(ErrorResponse("NOT_FOUND", "Rule not found"))
        ShoppingCategoryRulesTable.deleteWhere { (ShoppingCategoryRulesTable.normalizedName eq normalized) and (ShoppingCategoryRulesTable.listScope eq statsScope) }
        RuleResult.Ok(existing.toCategoryRuleDto())
    }

    // ---- Suggestions -----------------------------------------------------

    suspend fun suggestions(listId: UUID?, q: String?, limit: Int): List<ShoppingSuggestionDto> = dbQuery {
        val statsScope = ShoppingCatalog.statsScopeFor(listId)
        val liveKeys = ShoppingCatalog.liveKeysForList(listId)
        val rules = ShoppingCatalog.loadRulesForList(listId) // scoped dictionary (#501)
        fun liveCat(cat: String) = if (cat in liveKeys) cat else GroceryCatalog.OTHER
        val merged = LinkedHashMap<String, ShoppingSuggestionDto>()
        // The scoped rules dictionary is the day-one baseline: the shared grocery names for a shared
        // list, the list's own rule names for an own list (empty until it defines any).
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

    // ---- Batch add -------------------------------------------------------

    /** The freshly created and merged-into items of a batch add, plus how many exact dupes were skipped. */
    class BatchOutcome(val created: List<ShoppingItemDto>, val updated: List<ShoppingItemDto>, val skipped: Int)

    /** Returns null when [listId] names a missing list (→ 404). [lines] is already name-filtered by the caller. */
    suspend fun batchAdd(listId: UUID?, lines: List<ShoppingLineInput>, username: String): BatchOutcome? {
        val created = mutableListOf<ShoppingItemDto>()
        val updated = mutableListOf<ShoppingItemDto>()
        val usedNames = mutableListOf<String>() // bare names to tally after the tx (best-effort)
        var skipped = 0

        val listExists = dbQuery {
            if (listId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }.empty()) {
                return@dbQuery false
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

        if (!listExists) return null
        recordUsages(usedNames, listId) // best-effort tally in a separate tx (never rolls back the batch)
        return BatchOutcome(created, updated, skipped)
    }

    // ---- Items -----------------------------------------------------------

    suspend fun listItems(): List<ShoppingItemDto> = dbQuery {
        ShoppingItemsTable.selectAll().map { it.toDto() }
    }

    sealed interface CreateItemResult {
        data class Ok(val item: ShoppingItemDto) : CreateItemResult
        data class Invalid(val error: ErrorResponse) : CreateItemResult
    }

    /**
     * Caller has ensured [name] is non-blank. An unknown [listId] yields Invalid → 400 (mirroring the
     * pre-service route, which returned the NOT_FOUND-coded body with a 400 status here — unlike the
     * batch endpoint, which 404s; both behaviours are preserved).
     */
    suspend fun createItem(name: String, listId: UUID?, quantity: String?, username: String): CreateItemResult {
        val item = dbQuery {
            if (listId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq listId }.empty()) {
                return@dbQuery ShoppingService.CreateItemResult.Invalid(ErrorResponse("NOT_FOUND", "List not found"))
            }
            val id = UUID.randomUUID()
            val (resolvedCategory, resolvedIcon) = resolveForItem(name, ShoppingCatalog.loadRulesForList(listId), ShoppingCatalog.liveKeysForList(listId), ShoppingCatalog.statsScopeFor(listId)) // #412/#501: the item's list scope
            ShoppingItemsTable.insert {
                it[ShoppingItemsTable.id] = id
                it[ShoppingItemsTable.name] = name
                it[ShoppingItemsTable.listId] = listId
                it[checked] = false
                it[createdBy] = username
                it[createdAt] = Instant.now()
                it[category] = resolvedCategory
                it[icon] = resolvedIcon
                it[ShoppingItemsTable.quantity] = quantity?.takeIf { q -> q.isNotBlank() }?.trim()
            }
            CreateItemResult.Ok(ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto())
        }
        if (item is CreateItemResult.Invalid) return item
        recordUsages(listOf(name), listId) // best-effort tally in a separate tx (never rolls back the item)
        return item
    }

    sealed interface UpdateItemResult {
        data class Ok(val item: ShoppingItemDto) : UpdateItemResult
        data class Invalid(val error: ErrorResponse) : UpdateItemResult
        data object NotFound : UpdateItemResult
    }

    /** Caller has parsed [targetListId] and ensured any provided name is non-blank. */
    suspend fun updateItem(id: UUID, req: UpdateShoppingItemRequest, targetListId: UUID?): UpdateItemResult {
        // Manual category/icon override (#389/#390): blank = unchanged; a category must be a known key.
        val newCategory = req.category?.takeIf { it.isNotBlank() }
        // Icon override (#389/#390/#508): null = unchanged, "" = clear the override (fall back to
        // name/category auto-resolution), else the chosen svg-basename.
        val clearIcon = req.icon != null && req.icon.isBlank()
        val newIcon = req.icon?.takeIf { it.isNotBlank() }

        val result = dbQuery {
            val existing = ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.singleOrNull()
                ?: return@dbQuery UpdateItemResult.NotFound
            if (targetListId != null && ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq targetListId }.empty()) {
                return@dbQuery UpdateItemResult.Invalid(ErrorResponse("NOT_FOUND", "List not found"))
            }
            // category override must be a live catalog key (#411), validated against the item's
            // DESTINATION list's set (#412): the list it's being moved to, else its current list.
            val finalListId = if (req.listId != null) targetListId else existing[ShoppingItemsTable.listId]
            if (newCategory != null && newCategory !in ShoppingCatalog.liveKeysForList(finalListId)) {
                return@dbQuery UpdateItemResult.Invalid(ErrorResponse("INVALID_CATEGORY", "category must be a known key"))
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
            UpdateItemResult.Ok(ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.single().toDto())
        }

        // Remember the correction (best-effort, separate tx) so future adds of this name pick it up.
        // Scoped to the item's (destination) list so two own lists can remember it differently (#501).
        if (result is UpdateItemResult.Ok && (newCategory != null || newIcon != null)) {
            val dto = result.item
            rememberStatsPreference(dto.name, newCategory, newIcon, dto.listId?.let { runCatching { UUID.fromString(it) }.getOrNull() })
        }
        return result
    }

    /** Returns null when the item does not exist (→ 404). */
    suspend fun deleteItem(id: UUID): ShoppingItemDto? = dbQuery {
        val existing = ShoppingItemsTable.selectAll().where { ShoppingItemsTable.id eq id }.singleOrNull()
            ?: return@dbQuery null
        ShoppingItemsTable.deleteWhere { ShoppingItemsTable.id eq id }
        existing.toDto()
    }
}

// ---- Helpers (moved verbatim from ShoppingRoutes; each runs inside a transaction) ---------------

private fun scopeListExists(scopeId: UUID?): Boolean =
    scopeId == null || !ShoppingListsTable.selectAll().where { ShoppingListsTable.id eq scopeId }.empty()

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
// transactions so a concurrent stats write can never roll back the user's actual item create/update.

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
 * item write). Call AFTER the item transaction has committed.
 */
private suspend fun recordUsages(rawNames: List<String>, listId: UUID?) {
    val entries = rawNames.mapNotNull { raw ->
        val key = GroceryCatalog.normalize(raw)
        if (key.isBlank()) null else key to raw.trim()
    }
    if (entries.isEmpty()) return
    runCatching {
        dbQuery {
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
private suspend fun rememberStatsPreference(rawName: String, categoryOverride: String?, iconOverride: String?, listId: UUID?) {
    if (categoryOverride == null && iconOverride == null) return
    val key = GroceryCatalog.normalize(rawName)
    if (key.isBlank()) return
    runCatching {
        dbQuery {
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

/** Short units recognised when parsing a "200 g Mehl" shopping label back into parts (backend-internal, #103). */
private val KNOWN_UNITS = setOf(
    "g", "kg", "mg", "ml", "l", "el", "tl", "stk", "stück", "prise",
    "bund", "dose", "pkg", "pck", "tasse", "cup", "msp",
)

private data class ParsedQty(val amount: Double?, val unit: String?, val name: String)

/**
 * Split a label like "200 g Mehl" into amount / unit / name. A leading number (comma decimals allowed)
 * is the amount; a following token that is a known unit (KNOWN_UNITS) is the unit; the rest is the
 * name. Without a leading number the whole string is the name (amount/unit null). See issue #47/#103.
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

// ---- Mappers ------------------------------------------------------------

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
