package com.homebase.shopping

import com.homebase.db.ShoppingCategoriesTable
import com.homebase.db.ShoppingCategoryRulesTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * DB-backed view over the editable shopping catalog (#411): the category LIST (`shopping_categories`)
 * and the name→category+icon auto-resolve dictionary (`shopping_category_rules`). Seeds both from
 * [GroceryCatalog] on first startup and resolves item names against the live DB rules.
 *
 * Deliberately cache-free: the tables are tiny and read within the caller's existing transaction (a
 * per-operation [liveKeys] / [loadRules] load), so there is no process-wide mutable cache to leak
 * between tests (each runs against its own H2 DB) or to invalidate on every CRUD write. [GroceryCatalog]
 * stays the seed source + the `normalize` algorithm.
 */
object ShoppingCatalog {

    /**
     * Seed both catalog tables from [GroceryCatalog] into their empty tables — idempotent, SEED_USERS
     * style. Runs at startup (after Flyway) and in the test setup (after SchemaUtils.create). Category
     * rows are flagged `is_builtin = true`; rules are deduped by normalized name (last wins, matching
     * the old in-memory map). OTHER stays the protected fallback.
     */
    fun seedIfEmpty() {
        transaction {
            if (ShoppingCategoriesTable.selectAll().limit(1).empty()) {
                GroceryCatalog.categories.forEach { c ->
                    ShoppingCategoriesTable.insert {
                        it[key] = c.key
                        it[label] = c.label
                        it[emoji] = c.emoji
                        it[sortOrder] = c.order
                        it[isBuiltin] = true
                    }
                }
            }
            if (ShoppingCategoryRulesTable.selectAll().limit(1).empty()) {
                GroceryCatalog.seed.associateBy { it.normalized }.values.forEach { e ->
                    ShoppingCategoryRulesTable.insert {
                        it[normalizedName] = e.normalized
                        it[displayName] = e.name
                        it[category] = e.category
                        it[icon] = e.icon
                    }
                }
            }
        }
    }

    /** The live set of category keys. Call inside a transaction; load once per operation. */
    fun liveKeys(): Set<String> =
        ShoppingCategoriesTable.selectAll().mapTo(HashSet()) { it[ShoppingCategoriesTable.key] }

    /** Load the auto-resolve dictionary into an in-memory matcher. Call inside a transaction, once per operation. */
    fun loadRules(): RuleSet = RuleSet(
        ShoppingCategoryRulesTable.selectAll().map {
            RuleSet.Rule(
                it[ShoppingCategoryRulesTable.normalizedName],
                it[ShoppingCategoryRulesTable.displayName],
                it[ShoppingCategoryRulesTable.category],
                it[ShoppingCategoryRulesTable.icon],
            )
        },
    )

    /**
     * Resolve a written name to its category + icon against the loaded [rules], but never return a
     * category the household has deleted — fall back to [GroceryCatalog.OTHER] (kept, protected),
     * preserving the matched icon. [liveKeys] is the current category key set (load once per op).
     */
    fun resolve(name: String, rules: RuleSet, liveKeys: Set<String>): GroceryCatalog.Resolution {
        val r = rules.match(name)
        return if (r.category in liveKeys) r else r.copy(category = GroceryCatalog.OTHER)
    }

    /**
     * In-memory matcher built from the DB rules (per operation). Holds the resolution algorithm ported
     * 1:1 from the old GroceryCatalog: exact normalized match, then a longest-substring fallback
     * ("Bio Tomaten" → tomaten, "Tomate" → tomaten), else OTHER + the cart icon.
     */
    class RuleSet(private val rules: List<Rule>) {
        data class Rule(val normalized: String, val display: String, val category: String, val icon: String)

        private val byNormalized: Map<String, GroceryCatalog.Resolution> =
            rules.associate { it.normalized to GroceryCatalog.Resolution(it.category, it.icon) }
        private val byLengthDesc: List<Pair<String, GroceryCatalog.Resolution>> =
            byNormalized.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

        fun match(name: String): GroceryCatalog.Resolution {
            val n = GroceryCatalog.normalize(name)
            if (n.isBlank()) return GroceryCatalog.Resolution(GroceryCatalog.OTHER, GroceryCatalog.DEFAULT_ICON)
            byNormalized[n]?.let { return it }
            if (n.length >= 3) {
                byLengthDesc.firstOrNull { (key, _) -> key.length >= 3 && (n.contains(key) || key.contains(n)) }
                    ?.let { return it.second }
            }
            return GroceryCatalog.Resolution(GroceryCatalog.OTHER, GroceryCatalog.DEFAULT_ICON)
        }

        /** Distinct entries (first written form per normalized name) for the suggestions baseline. */
        fun allEntries(): List<GroceryCatalog.CatalogItem> =
            rules.distinctBy { it.normalized }
                .map { GroceryCatalog.CatalogItem(it.display, it.category, it.icon, it.normalized) }
    }
}
