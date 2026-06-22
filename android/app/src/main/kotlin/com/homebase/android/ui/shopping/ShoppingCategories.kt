package com.homebase.android.ui.shopping

import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingItemDto

/**
 * Presentation metadata for a grocery category (#389): the German header label + emoji + (route)
 * order. The live, editable catalog (#411) is fetched from GET /shopping/categories and threaded
 * into the helpers below as the `categories` argument; [BUILTIN_CATEGORIES] is the seed mirror used
 * as the initial state / offline fallback until that fetch returns. The backend resolves and stores
 * each item's category *key*; this only maps key → header presentation. Mirror of the web
 * `shoppingCategories.tsx`.
 */
data class GroceryCategory(val key: String, val label: String, val emoji: String)

/** Map a fetched [ShoppingCategoryDto] to the presentation shape used across the shopping UI. */
fun ShoppingCategoryDto.toGrocery(): GroceryCategory = GroceryCategory(key, label, emoji)

/**
 * Seed mirror of the backend `GroceryCatalog.categories` — initial state / offline fallback only.
 * Keep in sync with GroceryCatalog.kt's `categories` (it seeds the same rows into shopping_categories
 * on first startup) and the web `BUILTIN_CATEGORIES`.
 */
val BUILTIN_CATEGORIES: List<GroceryCategory> = listOf(
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

/** The protected OTHER/fallback key — items of a deleted category land here; it can't be deleted. */
const val OTHER_CATEGORY_KEY = "OTHER"

/** Hardcoded fallback when a catalog somehow lacks the OTHER bucket (should never happen). */
private val FALLBACK_OTHER = GroceryCategory(OTHER_CATEGORY_KEY, "Sonstiges", "❓")

/** The OTHER/fallback bucket within [categories] (or a hardcoded default if the catalog lacks it). */
private fun otherOf(categories: List<GroceryCategory>): GroceryCategory =
    categories.firstOrNull { it.key == OTHER_CATEGORY_KEY } ?: FALLBACK_OTHER

/** Header label + emoji for a category key against [categories]; unknown/missing → the OTHER bucket. */
fun categoryMeta(key: String?, categories: List<GroceryCategory>): GroceryCategory =
    (key?.let { k -> categories.firstOrNull { it.key == k } }) ?: otherOf(categories)

/**
 * Bucket items by their category key against [categories] and return non-empty groups in catalog
 * order. Items with no/unknown category fall into OTHER (rendered last); input order is preserved
 * within a group (callers pass newest-first).
 */
fun groupByCategory(
    items: List<ShoppingItemDto>,
    categories: List<GroceryCategory>,
): List<Pair<GroceryCategory, List<ShoppingItemDto>>> {
    val order = categories.withIndex().associate { (i, c) -> c.key to i }
    val known = categories.mapTo(HashSet()) { it.key }
    val otherKey = otherOf(categories).key
    val buckets = LinkedHashMap<String, MutableList<ShoppingItemDto>>()
    for (item in items) {
        val key = item.category?.takeIf { known.contains(it) } ?: otherKey
        buckets.getOrPut(key) { mutableListOf() }.add(item)
    }
    return buckets.entries
        .sortedBy { order[it.key] ?: Int.MAX_VALUE }
        .map { categoryMeta(it.key, categories) to it.value.toList() }
}
