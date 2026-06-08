package com.homebase.android

import com.homebase.android.data.model.IngredientDto
import com.homebase.android.ui.recipes.groupIngredientsBySection
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

    private fun ing(name: String, section: String? = null, sortOrder: Int = 0) =
        IngredientDto(id = name, name = name, section = section, sortOrder = sortOrder)

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
}
