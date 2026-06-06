package com.homebase.android

import com.homebase.android.ui.recipes.parseIngredientLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the free-text ingredient-line parser used when creating a recipe.
 * Guards the #92 fix: a short first word of a multi-word name must not be swallowed as a unit.
 */
class ParseIngredientLineTest {

    @Test
    fun `multi-word name with short first word keeps the whole name`() {
        val parsed = parseIngredientLine("2 rote Paprika")
        assertEquals("rote Paprika", parsed.name)
        assertNull(parsed.unit)
        assertEquals(2.0, parsed.amount!!, 0.0)
    }

    @Test
    fun `multi-word name with three-letter first word keeps the whole name`() {
        val parsed = parseIngredientLine("3 Bio Eier")
        assertEquals("Bio Eier", parsed.name)
        assertNull(parsed.unit)
        assertEquals(3.0, parsed.amount!!, 0.0)
    }

    @Test
    fun `known unit is parsed as unit (non-regression)`() {
        val parsed = parseIngredientLine("200 g Mehl")
        assertEquals("Mehl", parsed.name)
        assertEquals("g", parsed.unit)
        assertEquals(200.0, parsed.amount!!, 0.0)
    }

    @Test
    fun `comma decimal amount is parsed`() {
        val parsed = parseIngredientLine("0,5 l Milch")
        assertEquals("Milch", parsed.name)
        assertEquals("l", parsed.unit)
        assertEquals(0.5, parsed.amount!!, 0.0)
    }

    @Test
    fun `line without a leading number is a name-only ingredient`() {
        val parsed = parseIngredientLine("Salz")
        assertEquals("Salz", parsed.name)
        assertNull(parsed.unit)
        assertNull(parsed.amount)
    }

    /**
     * A short token that is NOT in KNOWN_UNITS stays part of the name — the accepted fallback from
     * the #92 / #47 whitelist change (we no longer guess units). "Glas" is not a recognised unit,
     * so it must remain in the name instead of being swallowed. Mirrors the backend test
     * "keeps a short unknown unit as a separate line" (PR #89).
     */
    @Test
    fun `unknown short unit stays in the name`() {
        val parsed = parseIngredientLine("2 Glas Milch")
        assertEquals("Glas Milch", parsed.name)
        assertNull(parsed.unit)
        assertEquals(2.0, parsed.amount!!, 0.0)
    }
}
