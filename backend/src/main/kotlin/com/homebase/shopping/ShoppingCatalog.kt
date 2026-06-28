package com.homebase.shopping

import com.homebase.db.ShoppingCategoriesTable
import com.homebase.db.ShoppingCategoryRulesTable
import org.jetbrains.exposed.sql.SortOrder
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

    /**
     * Load the auto-resolve dictionary into an in-memory matcher. Call inside a transaction, once per
     * operation. Ordered by normalized name so the longest-substring tiebreak is deterministic
     * regardless of DB row order (the length sort is stable).
     */
    fun loadRules(): RuleSet = RuleSet(
        ShoppingCategoryRulesTable.selectAll().orderBy(ShoppingCategoryRulesTable.normalizedName to SortOrder.ASC).map {
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
     * In-memory matcher built from the DB rules (per operation). Resolution order:
     *  1. exact normalized match,
     *  2. multi-word: a whole word equals a rule key ("Bio Tomaten" → tomaten),
     *  3. singular/plural / minor declension: a key differs from the name by ≤2 trailing chars
     *     ("Tomate" → tomaten),
     *  4. compound head noun: the longest key the name ENDS with ("Vollmilch" → milch),
     * else OTHER + the cart icon.
     *
     * Deliberately NOT a free substring match (#441): the old `n.contains(key)` matched a category-
     * carrying PREFIX and mis-categorized German compounds — "Leberkäse" → käse (DAIRY), "Apfelschorle"
     * → apfel (PRODUCE). German compounds carry their category in the LAST noun, so step 4 matches only
     * the suffix; a prefix never wins. The few compounds whose tail lies (Leberkäse/Fleischkäse end in
     * "käse" yet are meat) are pinned as exact seed entries, resolved at step 1.
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
            // 2) multi-word: prefer the longest whole word that is itself a known key.
            val tokens = n.split(' ')
            if (tokens.size > 1) {
                tokens.sortedByDescending { it.length }
                    .firstNotNullOfOrNull { tok -> byNormalized[tok] }
                    ?.let { return it }
            }
            // 3) singular/plural: a key that is the name ± a short (≤2 char) suffix.
            if (n.length >= 4) {
                byLengthDesc.firstOrNull { (key, _) -> key.length >= 4 && pluralish(n, key) }
                    ?.let { return it.second }
            }
            // 4) German compound head noun: a compound is categorised by its LAST noun, so match the
            // longest key that the name ENDS with ("Vollmilch"→milch, "Leberwurst"→wurst, "Käsebrot"
            // →brot). This is NOT the old free substring match — it only looks at the suffix, so a
            // category-carrying PREFIX never wins ("Apfelschorle"↛apfel).
            // Guard the handful of compounds whose tail LIES first (German "…käse" that is meat). They
            // are also pinned as exact seed entries, but seeds only reach a fresh rule table — on an
            // already-seeded DB the generic "käse"→DAIRY rule would otherwise win via endsWith, so this
            // code-level guard is what keeps "Leberkäse" correct everywhere.
            HEAD_NOUN_LIES[n]?.let { return it }
            if (n.length >= 5) {
                byLengthDesc.firstOrNull { (key, _) -> key.length >= 4 && key.length < n.length && n.endsWith(key) }
                    ?.let { return it.second }
            }
            return GroceryCatalog.Resolution(GroceryCatalog.OTHER, GroceryCatalog.DEFAULT_ICON)
        }

        /**
         * True if one normalized word is the other plus up to two trailing chars — covers German
         * plural/declension endings ("tomate"/"tomaten", "joghurt"/"joghurts") while rejecting
         * compounds ("apfel"/"apfelschorle", diff 7). Symmetric; the shorter must be a prefix of the
         * longer (so "käse"/"leberkäse" — not a prefix — never matches).
         */
        private fun pluralish(a: String, b: String): Boolean {
            val short = if (a.length <= b.length) a else b
            val long = if (a.length <= b.length) b else a
            return long.startsWith(short) && long.length - short.length <= 2
        }

        /** Distinct entries (first written form per normalized name) for the suggestions baseline. */
        fun allEntries(): List<GroceryCatalog.CatalogItem> =
            rules.distinctBy { it.normalized }
                .map { GroceryCatalog.CatalogItem(it.display, it.category, it.icon, it.normalized) }

        companion object {
            // Compounds whose head noun lies about the category: German "…käse" that is actually meat.
            // Kept in code (not the DB seed) so the guard works on already-seeded DBs too, where the
            // generic "käse"→DAIRY rule would otherwise win the endsWith step. Tiny, closed set.
            private val HEAD_NOUN_LIES: Map<String, GroceryCatalog.Resolution> = listOf(
                "Leberkäse", "Leberkäs", "Fleischkäse",
            ).associate { GroceryCatalog.normalize(it) to GroceryCatalog.Resolution("MEAT_FISH", "🍖") }
        }
    }
}
