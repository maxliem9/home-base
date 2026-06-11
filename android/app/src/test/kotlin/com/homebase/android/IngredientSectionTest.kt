package com.homebase.android

import com.homebase.android.data.model.IngredientDto
import com.homebase.android.ui.recipes.groupIngredientsBySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Detail-page section grouping (issue #123): ingredients arrive ordered by sortOrder, and
 * [groupIngredientsBySection] reconstructs the named sections by collapsing consecutive runs.
 * The editor's create/round-trip path is covered by [StructuredIngredientEditorTest] (#28).
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
