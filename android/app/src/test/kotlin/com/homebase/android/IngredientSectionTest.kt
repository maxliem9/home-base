package com.homebase.android

import com.homebase.android.data.model.IngredientDto
import com.homebase.android.ui.recipes.groupIngredientsBySection
import com.homebase.android.ui.recipes.ingredientsToText
import com.homebase.android.ui.recipes.parseIngredients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the free-text "# Abschnitt" section convention and the detail-page grouping
 * (issue #123). The web editor uses structured section rows; Android mirrors them through a
 * lightweight "#"-prefixed-line convention on create and groups them on display.
 */
class IngredientSectionTest {

    private fun ing(
        name: String,
        amount: Double? = null,
        unit: String? = null,
        section: String? = null,
        sortOrder: Int = 0,
    ) = IngredientDto(id = name, name = name, amount = amount, unit = unit, section = section, sortOrder = sortOrder)

    @Test
    fun `hash lines start a section that the following ingredients carry`() {
        val parsed = parseIngredients(
            """
            # Boden
            200 g Mehl
            Butter
            # Topping
            500 g Quark
            """.trimIndent(),
        )
        assertEquals(listOf("Mehl", "Butter", "Quark"), parsed.map { it.name })
        assertEquals(listOf("Boden", "Boden", "Topping"), parsed.map { it.section })
        assertEquals("g", parsed[0].unit)
        assertEquals(200.0, parsed[0].amount!!, 0.0)
    }

    @Test
    fun `ingredients before any hash line have no section`() {
        val parsed = parseIngredients("Salz\n# Wuerze\nPfeffer")
        assertNull(parsed[0].section)
        assertEquals("Wuerze", parsed[1].section)
    }

    @Test
    fun `a bare hash resets back to the top group`() {
        val parsed = parseIngredients("# Boden\nMehl\n#\nSalz")
        assertEquals("Boden", parsed[0].section)
        assertNull(parsed[1].section)
    }

    @Test
    fun `grouping collapses consecutive runs and keeps the top group header-less`() {
        val groups = groupIngredientsBySection(
            listOf(
                ing("Salz"),
                ing("Mehl", section = "Boden"),
                ing("Butter", section = "Boden"),
                ing("Quark", section = "Topping"),
            ),
        )
        assertEquals(3, groups.size)
        assertNull(groups[0].first)
        assertEquals(listOf("Salz"), groups[0].second.map { it.name })
        assertEquals("Boden", groups[1].first)
        assertEquals(listOf("Mehl", "Butter"), groups[1].second.map { it.name })
        assertEquals("Topping", groups[2].first)
    }

    @Test
    fun `two non-adjacent sections with the same name stay separate`() {
        val groups = groupIngredientsBySection(
            listOf(
                ing("A", section = "X"),
                ing("B", section = "Y"),
                ing("C", section = "X"),
            ),
        )
        assertEquals(listOf("X", "Y", "X"), groups.map { it.first })
    }

    @Test
    fun `serialising re-creates the hash section headers and ingredient lines`() {
        val text = ingredientsToText(
            listOf(
                ing("Salz", sortOrder = 0),
                ing("Mehl", amount = 200.0, unit = "g", section = "Boden", sortOrder = 1),
                ing("Eier", amount = 2.0, section = "Boden", sortOrder = 2),
                ing("Quark", amount = 500.0, unit = "g", section = "Topping", sortOrder = 3),
            ),
        )
        assertEquals(
            """
            Salz
            # Boden
            200 g Mehl
            2 Eier
            # Topping
            500 g Quark
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `edit round-trip preserves names, amounts, units and sections`() {
        // Pre-filling the editor (ingredientsToText) and saving it again (parseIngredients) must
        // not lose the sections from issue #123 — the core regression issue #11 guards against.
        val original = listOf(
            ing("Zwiebel", amount = 1.0, section = null, sortOrder = 0),
            ing("Mehl", amount = 200.0, unit = "g", section = "Boden", sortOrder = 1),
            ing("Butter", amount = 1.5, unit = "EL", section = "Boden", sortOrder = 2),
            ing("Quark", amount = 500.0, unit = "g", section = "Topping", sortOrder = 3),
        )

        val parsed = parseIngredients(ingredientsToText(original))

        assertEquals(original.map { it.name }, parsed.map { it.name })
        assertEquals(original.map { it.amount }, parsed.map { it.amount })
        assertEquals(original.map { it.unit }, parsed.map { it.unit })
        assertEquals(original.map { it.section }, parsed.map { it.section })
    }
}
