package com.homebase.android

import com.homebase.android.ui.recipes.IngredientDraft
import com.homebase.android.ui.recipes.parseIngredientLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Free-text/paste ingredient parsing (#166): a leading number / fraction / range becomes the
 * amount, otherwise the whole line stays the name (never silently stores a wrong value).
 * Fractions → decimal, ranges → lower bound. Must stay identical to the web parser
 * (web/src/components/recipeIngredients.ts).
 */
class ParseIngredientLineTest {

    @Test
    fun `keeps the existing plain 200 g Mehl case`() {
        assertEquals(IngredientDraft(name = "Mehl", amount = "200", unit = "g"), parseIngredientLine("200 g Mehl"))
    }

    @Test
    fun `keeps a unit-less amount line (3 Eier)`() {
        assertEquals(IngredientDraft(name = "Eier", amount = "3"), parseIngredientLine("3 Eier"))
    }

    @Test
    fun `leaves a plain-text line untouched (Salz)`() {
        assertEquals(IngredientDraft(name = "Salz"), parseIngredientLine("Salz"))
    }

    @Test
    fun `accepts a decimal amount with comma (1,5 - 1_5)`() {
        assertEquals(IngredientDraft(name = "Milch", amount = "1.5", unit = "l"), parseIngredientLine("1,5 l Milch"))
    }

    @Test
    fun `parses a simple fraction to a decimal (1 over 2 TL Zimt)`() {
        assertEquals(IngredientDraft(name = "Zimt", amount = "0.5", unit = "TL"), parseIngredientLine("1/2 TL Zimt"))
    }

    @Test
    fun `parses a recurring fraction with stable 3-decimal rounding (1 over 3)`() {
        assertEquals(IngredientDraft(name = "Reis", amount = "0.333", unit = "Tasse"), parseIngredientLine("1/3 Tasse Reis"))
    }

    @Test
    fun `parses a mixed number (1 1 over 2 Tassen)`() {
        assertEquals(IngredientDraft(name = "Mehl", amount = "1.5", unit = "Tassen"), parseIngredientLine("1 1/2 Tassen Mehl"))
    }

    @Test
    fun `uses the lower bound of a range (1-2 Eier)`() {
        assertEquals(IngredientDraft(name = "Eier", amount = "1"), parseIngredientLine("1-2 Eier"))
    }

    @Test
    fun `uses the lower bound of a decimal range (0,5-1 TL Salz)`() {
        assertEquals(IngredientDraft(name = "Salz", amount = "0.5", unit = "TL"), parseIngredientLine("0,5-1 TL Salz"))
    }

    @Test
    fun `does not split a fraction with a zero denominator (1 over 0 stays the name)`() {
        assertEquals(IngredientDraft(name = "1/0 weird"), parseIngredientLine("1/0 weird"))
    }

    @Test
    fun `does not treat a non-numeric leading token as an amount`() {
        assertEquals(IngredientDraft(name = "Saft einer Zitrone"), parseIngredientLine("Saft einer Zitrone"))
    }

    @Test
    fun `does not mis-parse a numeric-looking word (200ml Wasser, no space)`() {
        assertEquals(IngredientDraft(name = "200ml Wasser"), parseIngredientLine("200ml Wasser"))
    }

    // 3/80 is a 4th-decimal tie where Java "%.3f" and JS toFixed historically diverged (0.038 vs
    // 0.037). Both parsers now round via Math.round(n*1000) -> 0.038 identically; this locks parity.
    @Test
    fun `rounds a fraction tie identically to web (3 over 80 - 0_038)`() {
        assertEquals(IngredientDraft(name = "Mehl", amount = "0.038", unit = "g"), parseIngredientLine("3/80 g Mehl"))
    }

    @Test
    fun `strips only a single trailing dot from the unit token (g_dotdot is not a unit)`() {
        assertEquals(IngredientDraft(name = "g.. Mehl", amount = "2"), parseIngredientLine("2 g.. Mehl"))
    }
}
