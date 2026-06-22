package com.homebase.android

import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.ui.shopping.BUILTIN_CATEGORIES
import com.homebase.android.ui.shopping.GroceryCategory
import com.homebase.android.ui.shopping.categoryMeta
import com.homebase.android.ui.shopping.groupByCategory
import com.homebase.android.ui.shopping.toGrocery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the shopping category presentation helpers (#389/#400/#411): the seed mirror
 * [BUILTIN_CATEGORIES] (offline fallback), `categoryMeta` (key → header meta) and `groupByCategory`
 * (bucket open items by category in catalog order, unknown/null → OTHER last). After #411 the helpers
 * take the (now editable, fetched) catalog as a PARAMETER, so the tests pass it in explicitly.
 */
class ShoppingCategoriesTest {

    private fun item(
        id: String,
        category: String? = null,
        name: String = "x",
    ) = ShoppingItemDto(
        id = id, name = name, checked = false,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
        category = category,
    )

    // --- The seed mirror itself ------------------------------------------------------------------

    @Test
    fun `BUILTIN_CATEGORIES mirrors the backend catalog (10 entries, OTHER last)`() {
        assertEquals(10, BUILTIN_CATEGORIES.size)
        assertEquals(
            listOf(
                "PRODUCE", "BAKERY", "DAIRY", "MEAT_FISH", "FROZEN",
                "PANTRY", "SNACKS", "DRINKS", "HOUSEHOLD", "OTHER",
            ),
            BUILTIN_CATEGORIES.map { it.key },
        )
        assertEquals("OTHER", BUILTIN_CATEGORIES.last().key)
        // No blank labels/emojis — every header renders something.
        assertTrue(BUILTIN_CATEGORIES.all { it.label.isNotBlank() && it.emoji.isNotBlank() })
    }

    @Test
    fun `toGrocery maps a fetched category DTO to its presentation shape`() {
        val dto = ShoppingCategoryDto(key = "GRILL", label = "Grillen", emoji = "🔥", sortOrder = 3, isBuiltin = false)
        assertEquals(GroceryCategory("GRILL", "Grillen", "🔥"), dto.toGrocery())
    }

    // --- categoryMeta ----------------------------------------------------------------------------

    @Test
    fun `categoryMeta resolves a known key to its header meta`() {
        val produce = categoryMeta("PRODUCE", BUILTIN_CATEGORIES)
        assertEquals("PRODUCE", produce.key)
        assertEquals("Obst & Gemüse", produce.label)
        assertEquals("🥦", produce.emoji)
    }

    @Test
    fun `categoryMeta maps an unknown key to the OTHER bucket`() {
        assertEquals("OTHER", categoryMeta("NOT_A_REAL_KEY", BUILTIN_CATEGORIES).key)
    }

    @Test
    fun `categoryMeta maps a null key to the OTHER bucket`() {
        assertEquals("OTHER", categoryMeta(null, BUILTIN_CATEGORIES).key)
    }

    @Test
    fun `categoryMeta resolves against a custom catalog (editable, #411)`() {
        // A household-added category beyond the builtins resolves from the passed catalog.
        val custom = BUILTIN_CATEGORIES + GroceryCategory("GRILL", "Grillen", "🔥")
        val meta = categoryMeta("GRILL", custom)
        assertEquals("GRILL", meta.key)
        assertEquals("Grillen", meta.label)
        assertEquals("🔥", meta.emoji)
    }

    // --- groupByCategory -------------------------------------------------------------------------

    @Test
    fun `groupByCategory buckets items into catalog order regardless of input order`() {
        // Deliberately out of route order: DRINKS (7) before PRODUCE (0) before DAIRY (2).
        val items = listOf(
            item("a", "DRINKS"),
            item("b", "PRODUCE"),
            item("c", "DAIRY"),
        )

        val groups = groupByCategory(items, BUILTIN_CATEGORIES)

        // Only the three present categories appear, sorted by their catalog index.
        assertEquals(listOf("PRODUCE", "DAIRY", "DRINKS"), groups.map { it.first.key })
    }

    @Test
    fun `groupByCategory keeps multiple items per bucket in input order`() {
        val items = listOf(
            item("p1", "PRODUCE"),
            item("d1", "DAIRY"),
            item("p2", "PRODUCE"),
        )

        val groups = groupByCategory(items, BUILTIN_CATEGORIES)

        assertEquals(listOf("PRODUCE", "DAIRY"), groups.map { it.first.key })
        // Within PRODUCE, the two items keep their original relative order (newest-first preserved).
        assertEquals(listOf("p1", "p2"), groups.first { it.first.key == "PRODUCE" }.second.map { it.id })
    }

    @Test
    fun `groupByCategory routes null and unknown categories into a single OTHER bucket, rendered last`() {
        val items = listOf(
            item("u", "WHO_KNOWS"),   // unknown key
            item("n", null),          // null key (legacy row)
            item("p", "PRODUCE"),
        )

        val groups = groupByCategory(items, BUILTIN_CATEGORIES)

        // PRODUCE first, OTHER last; null + unknown collapse into the same OTHER bucket.
        assertEquals(listOf("PRODUCE", "OTHER"), groups.map { it.first.key })
        assertEquals("OTHER", groups.last().first.key)
        assertEquals(setOf("u", "n"), groups.last().second.map { it.id }.toSet())
    }

    @Test
    fun `groupByCategory honours a custom catalog order and surfaces a custom category`() {
        // A reordered + extended catalog: GRILL sits between PRODUCE and OTHER. An item carrying it
        // must group under GRILL (not fall into OTHER), proving the helper uses the passed catalog.
        val custom = listOf(
            GroceryCategory("PRODUCE", "Obst & Gemüse", "🥦"),
            GroceryCategory("GRILL", "Grillen", "🔥"),
            GroceryCategory("OTHER", "Sonstiges", "❓"),
        )
        val items = listOf(item("g", "GRILL"), item("p", "PRODUCE"), item("x", "UNKNOWN"))

        val groups = groupByCategory(items, custom)

        assertEquals(listOf("PRODUCE", "GRILL", "OTHER"), groups.map { it.first.key })
        assertEquals(listOf("g"), groups.first { it.first.key == "GRILL" }.second.map { it.id })
        assertEquals(listOf("x"), groups.first { it.first.key == "OTHER" }.second.map { it.id })
    }

    @Test
    fun `groupByCategory on an empty list returns no groups`() {
        assertTrue(groupByCategory(emptyList(), BUILTIN_CATEGORIES).isEmpty())
    }
}
