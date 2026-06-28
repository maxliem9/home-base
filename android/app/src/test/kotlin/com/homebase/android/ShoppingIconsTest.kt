package com.homebase.android

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.ui.shopping.ShoppingIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage of the Android icon resolution (#443) — the mirror of the web `shoppingCategories`/
 * `shoppingIconMap`. Verifies slug normalization and the override → name → category → misc chain.
 */
class ShoppingIconsTest {

    private fun item(name: String, category: String? = null, icon: String? = null) =
        ShoppingItemDto(id = "i", name = name, checked = false, createdBy = "a", createdAt = "t", category = category, icon = icon)

    @Test
    fun `slugify strips quantity and transliterates umlauts`() {
        assertEquals("moehren", ShoppingIcons.slugifyIconKey("500 g Möhren"))
        assertEquals("leberkaese", ShoppingIcons.slugifyIconKey("Leberkäse"))
        assertEquals("paprika", ShoppingIcons.slugifyIconKey("2 Paprika"))
    }

    @Test
    fun `item name resolves to its English icon asset`() {
        assertTrue(ShoppingIcons.assetForItem(item("Möhren")).endsWith("/items/carrots.svg"))
        assertTrue(ShoppingIcons.assetForItem(item("Leberkäse")).endsWith("/items/meatloaf.svg"))
        assertTrue(ShoppingIcons.assetForName("Apfelsaft", "DRINKS").endsWith("/items/juice.svg"))
    }

    @Test
    fun `explicit icon override wins over the name`() {
        assertTrue(ShoppingIcons.assetForItem(item("Tomaten", icon = "meatloaf")).endsWith("/items/meatloaf.svg"))
        // a legacy emoji in the icon field is not a basename → ignored, name resolution applies
        assertTrue(ShoppingIcons.assetForItem(item("Tomaten", icon = "🍅")).endsWith("/items/tomatoes.svg"))
    }

    @Test
    fun `unknown name falls back to its category icon, then misc`() {
        assertTrue(ShoppingIcons.assetForItem(item("Quinoa", category = "PRODUCE")).endsWith("/categories/produce.svg"))
        assertTrue(ShoppingIcons.assetForItem(item("Quinoa")).endsWith("/items/misc.svg"))
    }

    @Test
    fun `category icon resolves, unknown category is null`() {
        assertTrue(ShoppingIcons.assetForCategory("MEAT_FISH")!!.endsWith("/categories/meat-fish.svg"))
        assertEquals(null, ShoppingIcons.assetForCategory("NOPE"))
    }
}
