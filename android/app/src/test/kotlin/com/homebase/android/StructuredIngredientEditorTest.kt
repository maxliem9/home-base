package com.homebase.android

import com.homebase.android.data.model.IngredientDto
import com.homebase.android.ui.recipes.IngredientDraft
import com.homebase.android.ui.recipes.SectionDraft
import com.homebase.android.ui.recipes.sectionsFromIngredients
import com.homebase.android.ui.recipes.sectionsToIngredients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The structured ingredient editor (#28): amount/unit/name rows grouped into sections, replacing
 * the old free-text parser. Guards the two round-trip cases the free-text editor could not express:
 * a unit without an amount, and a numeric-looking name without an amount.
 */
class StructuredIngredientEditorTest {

    private fun ing(name: String, amount: Double? = null, unit: String? = null, section: String? = null, sortOrder: Int = 0) =
        IngredientDto(id = name, name = name, amount = amount, unit = unit, section = section, sortOrder = sortOrder)

    @Test
    fun `an empty recipe yields a single blank section to type into`() {
        val sections = sectionsFromIngredients(emptyList())
        assertEquals(1, sections.size)
        assertEquals("", sections[0].name)
        assertEquals(listOf(IngredientDraft()), sections[0].ingredients)
    }

    @Test
    fun `blank-name rows are dropped on save`() {
        val out = sectionsToIngredients(
            listOf(
                SectionDraft(
                    ingredients = listOf(
                        IngredientDraft(name = "Mehl", amount = "200", unit = "g"),
                        IngredientDraft(name = "   ", amount = "5", unit = "g"), // blank name → dropped
                    ),
                ),
            ),
        )
        assertEquals(listOf("Mehl"), out.map { it.name })
    }

    @Test
    fun `amount accepts comma or dot, blank becomes null`() {
        val out = sectionsToIngredients(
            listOf(
                SectionDraft(
                    ingredients = listOf(
                        IngredientDraft(name = "Milch", amount = "0,5", unit = "l"),
                        IngredientDraft(name = "Salz", amount = "", unit = ""),
                    ),
                ),
            ),
        )
        assertEquals(0.5, out[0].amount!!, 0.0)
        assertEquals("l", out[0].unit)
        assertNull(out[1].amount)
        assertNull(out[1].unit)
    }

    @Test
    fun `a unit without an amount survives (the free-text editor lost this)`() {
        val out = sectionsToIngredients(
            listOf(SectionDraft(ingredients = listOf(IngredientDraft(name = "Mehl", amount = "", unit = "g")))),
        )
        assertEquals("Mehl", out[0].name)
        assertNull(out[0].amount)
        assertEquals("g", out[0].unit)
    }

    @Test
    fun `a numeric-looking name without an amount is not mis-parsed`() {
        val out = sectionsToIngredients(
            listOf(SectionDraft(ingredients = listOf(IngredientDraft(name = "200 Gramm Spezialmehl", amount = "", unit = "")))),
        )
        assertEquals("200 Gramm Spezialmehl", out[0].name)
        assertNull(out[0].amount)
        assertNull(out[0].unit)
    }

    @Test
    fun `sections round-trip from a stored recipe back to inputs without loss`() {
        val stored = listOf(
            ing("Zwiebel", amount = 1.0, section = null, sortOrder = 0),
            ing("Mehl", amount = 200.0, unit = "g", section = "Boden", sortOrder = 1),
            ing("Butter", amount = 1.5, unit = "EL", section = "Boden", sortOrder = 2),
            ing("Quark", amount = 500.0, unit = "g", section = "Topping", sortOrder = 3),
        )
        val roundTripped = sectionsToIngredients(sectionsFromIngredients(stored))
        assertEquals(stored.map { it.name }, roundTripped.map { it.name })
        assertEquals(stored.map { it.amount }, roundTripped.map { it.amount })
        assertEquals(stored.map { it.unit }, roundTripped.map { it.unit })
        assertEquals(stored.map { it.section }, roundTripped.map { it.section })
    }
}
