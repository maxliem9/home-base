package com.homebase.android

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.ui.shopping.SHOPPING_CATEGORIES
import com.homebase.android.ui.shopping.categoryMeta
import com.homebase.android.ui.shopping.groupByCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the shopping category presentation helpers (#389/#400): the fixed mirror of
 * the backend `GroceryCatalog.categories`, `categoryMeta` (key → header meta) and `groupByCategory`
 * (bucket open items by category in shopping-route order, unknown/null → OTHER last).
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

    // --- The mirror itself -----------------------------------------------------------------------

    @Test
    fun `SHOPPING_CATEGORIES mirrors the backend catalog (10 entries, OTHER last)`() {
        assertEquals(10, SHOPPING_CATEGORIES.size)
        assertEquals(
            listOf(
                "PRODUCE", "BAKERY", "DAIRY", "MEAT_FISH", "FROZEN",
                "PANTRY", "SNACKS", "DRINKS", "HOUSEHOLD", "OTHER",
            ),
            SHOPPING_CATEGORIES.map { it.key },
        )
        assertEquals("OTHER", SHOPPING_CATEGORIES.last().key)
        // No blank labels/emojis — every header renders something.
        assertTrue(SHOPPING_CATEGORIES.all { it.label.isNotBlank() && it.emoji.isNotBlank() })
    }

    // --- categoryMeta ----------------------------------------------------------------------------

    @Test
    fun `categoryMeta resolves a known key to its header meta`() {
        val produce = categoryMeta("PRODUCE")
        assertEquals("PRODUCE", produce.key)
        assertEquals("Obst & Gemüse", produce.label)
        assertEquals("🥦", produce.emoji)
    }

    @Test
    fun `categoryMeta maps an unknown key to the OTHER bucket`() {
        val other = SHOPPING_CATEGORIES.last()
        assertSame(other, categoryMeta("NOT_A_REAL_KEY"))
        assertEquals("OTHER", categoryMeta("NOT_A_REAL_KEY").key)
    }

    @Test
    fun `categoryMeta maps a null key to the OTHER bucket`() {
        assertEquals("OTHER", categoryMeta(null).key)
    }

    // --- groupByCategory -------------------------------------------------------------------------

    @Test
    fun `groupByCategory buckets items into route order regardless of input order`() {
        // Deliberately out of route order: DRINKS (7) before PRODUCE (0) before DAIRY (2).
        val items = listOf(
            item("a", "DRINKS"),
            item("b", "PRODUCE"),
            item("c", "DAIRY"),
        )

        val groups = groupByCategory(items)

        // Only the three present categories appear, sorted by their route index.
        assertEquals(listOf("PRODUCE", "DAIRY", "DRINKS"), groups.map { it.first.key })
    }

    @Test
    fun `groupByCategory keeps multiple items per bucket in input order`() {
        val items = listOf(
            item("p1", "PRODUCE"),
            item("d1", "DAIRY"),
            item("p2", "PRODUCE"),
        )

        val groups = groupByCategory(items)

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

        val groups = groupByCategory(items)

        // PRODUCE first, OTHER last; null + unknown collapse into the same OTHER bucket.
        assertEquals(listOf("PRODUCE", "OTHER"), groups.map { it.first.key })
        assertEquals("OTHER", groups.last().first.key)
        assertEquals(setOf("u", "n"), groups.last().second.map { it.id }.toSet())
    }

    @Test
    fun `groupByCategory on an empty list returns no groups`() {
        assertTrue(groupByCategory(emptyList()).isEmpty())
    }
}
