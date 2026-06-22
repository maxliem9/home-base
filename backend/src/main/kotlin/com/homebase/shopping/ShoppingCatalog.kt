package com.homebase.shopping

import com.homebase.db.ShoppingCategoriesTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * DB-backed view over the editable shopping category catalog (#411). The category LIST lives in
 * `shopping_categories` (seeded from [GroceryCatalog] on first startup); this object seeds it and
 * guards categorization so a deleted category never leaks onto an item.
 *
 * Deliberately cache-free: the table is tiny and read within the caller's existing transaction
 * (a per-operation [liveKeys] load), so there is no process-wide mutable cache to leak between
 * tests (each test runs against its own H2 database) or to invalidate on every CRUD write.
 *
 * [GroceryCatalog] stays the seed source + the `normalize`/`resolve` algorithm; this wraps it with
 * the live DB category set. The per-name auto-resolve dictionary moves to the DB later (#411 PR B).
 */
object ShoppingCatalog {
    /**
     * Seed the catalog from [GroceryCatalog.categories] into an empty table — idempotent, SEED_USERS
     * style. Runs at startup (after Flyway) and in the test setup (after SchemaUtils.create). Seeded
     * rows are flagged `is_builtin = true`; OTHER is the protected fallback (never deletable).
     */
    fun seedIfEmpty() {
        transaction {
            if (!ShoppingCategoriesTable.selectAll().limit(1).empty()) return@transaction
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
    }

    /** The live set of category keys. Call inside a transaction; load once per operation. */
    fun liveKeys(): Set<String> =
        ShoppingCategoriesTable.selectAll().mapTo(HashSet()) { it[ShoppingCategoriesTable.key] }

    /**
     * Resolve a written name to its category + icon, but never return a category the household has
     * deleted — fall back to [GroceryCatalog.OTHER] (kept, protected), preserving the resolved icon.
     * [liveKeys] is the current key set (load once per operation via [liveKeys]).
     */
    fun resolve(name: String, liveKeys: Set<String>): GroceryCatalog.Resolution {
        val r = GroceryCatalog.resolve(name)
        return if (r.category in liveKeys) r else r.copy(category = GroceryCatalog.OTHER)
    }
}
