package com.homebase.android

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.ui.shopping.ShoppingQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirror of the web shoppingQuantity tests (#447): leading-quantity parse + display parts. */
class ShoppingQuantityTest {

    private fun item(name: String, quantity: String? = null) =
        ShoppingItemDto(id = "i", name = name, checked = false, createdBy = "a", createdAt = "t", quantity = quantity)

    @Test
    fun `splitQuantity splits a leading qty and unit, leaves plain names whole`() {
        assertEquals(ShoppingQuantity.Parts("Milch", "2 L"), ShoppingQuantity.splitQuantity("2 L Milch"))
        assertEquals(ShoppingQuantity.Parts("Bananen", "6 Stück"), ShoppingQuantity.splitQuantity("6 Stück Bananen"))
        assertEquals(ShoppingQuantity.Parts("Tomaten", null), ShoppingQuantity.splitQuantity("Tomaten"))
    }

    @Test
    fun `requireUnit only splits when a real unit is present`() {
        assertEquals(ShoppingQuantity.Parts("Mehl", "200 g"), ShoppingQuantity.splitQuantity("200 g Mehl", requireUnit = true))
        // bare count, no unit → keep whole (don't tear apart a brand / fruit count)
        assertEquals(ShoppingQuantity.Parts("3 Musketiere", null), ShoppingQuantity.splitQuantity("3 Musketiere", requireUnit = true))
        assertEquals(ShoppingQuantity.Parts("2 Äpfel", null), ShoppingQuantity.splitQuantity("2 Äpfel", requireUnit = true))
    }

    @Test
    fun `displayParts prefers an explicit quantity, else parses the name`() {
        assertEquals(ShoppingQuantity.Parts("Mehl", "500 g"), ShoppingQuantity.displayParts(item("Mehl", "500 g")))
        assertEquals(ShoppingQuantity.Parts("Mehl", "200 g"), ShoppingQuantity.displayParts(item("200 g Mehl")))
        val plain = ShoppingQuantity.displayParts(item("Tomaten"))
        assertEquals("Tomaten", plain.title)
        assertNull(plain.detail)
        // a blank explicit quantity is treated as unset → parse the name
        assertEquals("Tomaten", ShoppingQuantity.displayParts(item("Tomaten", "  ")).title)
    }
}
