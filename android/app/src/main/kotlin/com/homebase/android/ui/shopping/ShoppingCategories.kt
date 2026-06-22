package com.homebase.android.ui.shopping

import com.homebase.android.data.model.ShoppingItemDto

/**
 * Presentation metadata for the grocery categories (#389) — mirror of the web `shoppingCategories.tsx`
 * and the backend `GroceryCatalog.categories`. The backend resolves and stores each item's category
 * *key*; this fixed 10-entry mirror maps key → German header label + emoji + shopping-route order,
 * exactly the way the recipe categories are known to the client. Keep in sync with GroceryCatalog.kt.
 */
data class GroceryCategory(val key: String, val label: String, val emoji: String)

val SHOPPING_CATEGORIES: List<GroceryCategory> = listOf(
    GroceryCategory("PRODUCE", "Obst & Gemüse", "🥦"),
    GroceryCategory("BAKERY", "Backwaren", "🥐"),
    GroceryCategory("DAIRY", "Milchprodukte & Eier", "🧀"),
    GroceryCategory("MEAT_FISH", "Fleisch & Fisch", "🥩"),
    GroceryCategory("FROZEN", "Tiefkühl", "🧊"),
    GroceryCategory("PANTRY", "Vorrat", "🥫"),
    GroceryCategory("SNACKS", "Snacks & Süßes", "🍫"),
    GroceryCategory("DRINKS", "Getränke", "🥤"),
    GroceryCategory("HOUSEHOLD", "Haushalt & Hygiene", "🧽"),
    GroceryCategory("OTHER", "Sonstiges", "❓"),
)

/** Neutral fallback emoji for an item that carries no resolved icon (legacy/unknown). */
const val DEFAULT_ITEM_ICON = "🛒"

private val OTHER_CATEGORY = SHOPPING_CATEGORIES.last()
private val BY_KEY = SHOPPING_CATEGORIES.associateBy { it.key }
private val ORDER = SHOPPING_CATEGORIES.withIndex().associate { (i, c) -> c.key to i }

/** Header label + emoji for a category key; unknown/missing → the OTHER ("Sonstiges") bucket. */
fun categoryMeta(key: String?): GroceryCategory = key?.let { BY_KEY[it] } ?: OTHER_CATEGORY

/**
 * Bucket items by their category key and return non-empty groups in fixed shopping-route order.
 * Items with no/unknown category fall into OTHER (rendered last); input order is preserved within a
 * group (callers pass newest-first).
 */
fun groupByCategory(items: List<ShoppingItemDto>): List<Pair<GroceryCategory, List<ShoppingItemDto>>> {
    val buckets = LinkedHashMap<String, MutableList<ShoppingItemDto>>()
    for (item in items) {
        val key = item.category?.takeIf { BY_KEY.containsKey(it) } ?: OTHER_CATEGORY.key
        buckets.getOrPut(key) { mutableListOf() }.add(item)
    }
    return buckets.entries
        .sortedBy { ORDER[it.key] ?: Int.MAX_VALUE }
        .map { categoryMeta(it.key) to it.value.toList() }
}
