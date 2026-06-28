package com.homebase

import com.homebase.routes.RecipeImport
import com.homebase.routes.charsetFromContentType
import com.homebase.routes.isBlockedForImport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure JSON-LD → recipe-draft mapper (Issue #430). No network: these feed raw
 * JSON-LD / HTML strings to [RecipeImport] and assert the mapped draft. Covers a top-level Recipe
 * object, @graph, HowToStep instructions, ISO durations and missing fields.
 */
class RecipeImportTest {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private fun node(s: String): JsonObject = json.parseToJsonElement(s) as JsonObject

    @Test
    fun `maps a top-level Recipe object with all fields`() {
        val draft = RecipeImport.mapRecipeNode(
            node(
                """
                {
                  "@type": "Recipe",
                  "name": "Spaghetti Bolognese",
                  "description": "Klassiker.",
                  "recipeYield": "4 Portionen",
                  "prepTime": "PT20M",
                  "cookTime": "PT1H30M",
                  "recipeCategory": "Hauptgericht",
                  "recipeIngredient": ["500 g Hackfleisch", "2 Zwiebeln", "1/2 TL Salz"],
                  "recipeInstructions": ["Zwiebeln anbraten.", "Hackfleisch dazu."]
                }
                """.trimIndent()
            ),
            sourceUrl = "https://example.com/r",
        )
        assertEquals("Spaghetti Bolognese", draft.title)
        assertEquals("Klassiker.", draft.description)
        assertEquals(4, draft.servings)
        assertEquals(20, draft.prepTimeMinutes)
        assertEquals(90, draft.cookTimeMinutes)
        assertEquals("DINNER", draft.category) // "Hauptgericht" → fallback DINNER
        assertEquals("https://example.com/r", draft.sourceUrl)
        assertEquals(3, draft.ingredients.size)
        assertEquals("Hackfleisch", draft.ingredients[0].name)
        assertEquals(500.0, draft.ingredients[0].amount)
        assertEquals("g", draft.ingredients[0].unit)
        // "2 Zwiebeln": Zwiebeln is not a known unit → stays the name
        assertEquals("Zwiebeln", draft.ingredients[1].name)
        assertEquals(2.0, draft.ingredients[1].amount)
        assertNull(draft.ingredients[1].unit)
        // "1/2 TL Salz": fraction + known unit
        assertEquals("Salz", draft.ingredients[2].name)
        assertEquals(0.5, draft.ingredients[2].amount)
        assertEquals("TL", draft.ingredients[2].unit)
        assertEquals(2, draft.steps.size)
        assertEquals("Zwiebeln anbraten.", draft.steps[0].description)
    }

    @Test
    fun `finds the Recipe inside an @graph wrapper`() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"Site"},
              {"@type":["Recipe","Thing"],"name":"Pfannkuchen","recipeIngredient":["3 Eier"]}
            ]}
            </script>
            </head></html>
        """.trimIndent()
        val draft = RecipeImport.fromHtml(html)
        assertNotNull(draft)
        assertEquals("Pfannkuchen", draft.title)
        assertEquals(1, draft.ingredients.size)
        assertEquals("Eier", draft.ingredients[0].name)
        assertEquals(3.0, draft.ingredients[0].amount)
    }

    @Test
    fun `finds the Recipe inside a JSON array of nodes`() {
        val element = json.parseToJsonElement(
            """[{"@type":"Organization","name":"x"},{"@type":"Recipe","name":"Salat"}]"""
        )
        val recipe = RecipeImport.findRecipeNode(element)
        assertNotNull(recipe)
        assertEquals("Salat", RecipeImport.mapRecipeNode(recipe).title)
    }

    @Test
    fun `maps HowToStep instructions and HowToSection nesting`() {
        val draft = RecipeImport.mapRecipeNode(
            node(
                """
                {
                  "@type": "Recipe",
                  "name": "Torte",
                  "recipeInstructions": [
                    {"@type":"HowToStep","text":"Boden backen."},
                    {"@type":"HowToSection","name":"Creme","itemListElement":[
                      {"@type":"HowToStep","text":"Sahne schlagen."},
                      {"@type":"HowToStep","text":"Zucker dazu."}
                    ]}
                  ]
                }
                """.trimIndent()
            )
        )
        assertEquals(3, draft.steps.size)
        assertEquals("Boden backen.", draft.steps[0].description)
        assertEquals("Sahne schlagen.", draft.steps[1].description)
        assertEquals("Zucker dazu.", draft.steps[2].description)
    }

    @Test
    fun `splits a single-string instruction on newlines`() {
        val draft = RecipeImport.mapRecipeNode(
            node("""{"@type":"Recipe","name":"X","recipeInstructions":"Schritt 1\nSchritt 2\n\nSchritt 3"}""")
        )
        assertEquals(listOf("Schritt 1", "Schritt 2", "Schritt 3"), draft.steps.map { it.description })
    }

    @Test
    fun `missing fields fall back to sensible defaults`() {
        val draft = RecipeImport.mapRecipeNode(node("""{"@type":"Recipe","name":"Nur Titel"}"""))
        assertEquals("Nur Titel", draft.title)
        assertNull(draft.description)
        assertNull(draft.servings)
        assertNull(draft.prepTimeMinutes)
        assertNull(draft.cookTimeMinutes)
        assertEquals("DINNER", draft.category)
        assertTrue(draft.ingredients.isEmpty())
        assertTrue(draft.steps.isEmpty())
    }

    @Test
    fun `untitled recipe gets a placeholder title`() {
        val draft = RecipeImport.mapRecipeNode(node("""{"@type":"Recipe","recipeIngredient":["1 Ei"]}"""))
        assertEquals("Importiertes Rezept", draft.title)
    }

    @Test
    fun `category suggestion maps freetext`() {
        fun cat(c: String) = RecipeImport.mapRecipeNode(node("""{"@type":"Recipe","name":"x","recipeCategory":"$c"}""")).category
        assertEquals("DESSERT", cat("Nachtisch"))
        assertEquals("BREAKFAST", cat("Frühstück"))
        assertEquals("DRINK", cat("Cocktail"))
        assertEquals("SNACK", cat("Vorspeise"))
        assertEquals("DINNER", cat("Irgendwas"))
        assertEquals("DESSERT", cat("DESSERT"))
    }

    @Test
    fun `parses ISO durations and yields robustly`() {
        assertEquals(45, RecipeImport.parseIsoMinutes(json.parseToJsonElement("\"PT45M\"")))
        assertEquals(75, RecipeImport.parseIsoMinutes(json.parseToJsonElement("\"PT1H15M\"")))
        assertNull(RecipeImport.parseIsoMinutes(json.parseToJsonElement("\"später\"")))
        assertEquals(6, RecipeImport.parseYield(json.parseToJsonElement("\"Serves 6 people\"")))
        assertEquals(4, RecipeImport.parseYield(json.parseToJsonElement("[\"4 servings\",\"4\"]")))
        assertNull(RecipeImport.parseYield(json.parseToJsonElement("\"viele\"")))
    }

    @Test
    fun `mixed numbers and ranges in ingredient amounts`() {
        val mixed = RecipeImport.parseIngredientLine("1 1/2 TL Backpulver")
        assertEquals(1.5, mixed.amount)
        assertEquals("TL", mixed.unit)
        assertEquals("Backpulver", mixed.name)
        // range → lower bound
        val range = RecipeImport.parseIngredientLine("2-3 EL Öl")
        assertEquals(2.0, range.amount)
        assertEquals("EL", range.unit)
        // no leading number → whole line is the name
        val plain = RecipeImport.parseIngredientLine("Salz nach Geschmack")
        assertNull(plain.amount)
        assertEquals("Salz nach Geschmack", plain.name)
    }

    @Test
    fun `ingredient objects with a text field are read`() {
        val draft = RecipeImport.mapRecipeNode(
            node("""{"@type":"Recipe","name":"x","recipeIngredient":[{"@type":"thing","text":"100 g Zucker"}]}""")
        )
        assertEquals(1, draft.ingredients.size)
        assertEquals("Zucker", draft.ingredients[0].name)
        assertEquals(100.0, draft.ingredients[0].amount)
        assertEquals("g", draft.ingredients[0].unit)
    }

    @Test
    fun `returns null when no Recipe node is present`() {
        val html = """<html><script type="application/ld+json">{"@type":"WebPage","name":"x"}</script></html>"""
        assertNull(RecipeImport.fromHtml(html))
        assertNull(RecipeImport.fromHtml("<html><body>no json-ld here</body></html>"))
    }

    @Test
    fun `parses decimal-comma amounts in ingredient lines`() {
        val ing = RecipeImport.parseIngredientLine("1,5 l Wasser")
        assertEquals(1.5, ing.amount)
        assertEquals("l", ing.unit)
        assertEquals("Wasser", ing.name)
    }

    // --- SSRF address guard (pure, security-relevant — Issue #430) --------------------------

    @Test
    fun `blocks private, loopback, link-local and CGNAT addresses`() {
        // by-name resolution of literals never hits DNS
        fun addr(s: String) = InetAddress.getByName(s)
        assertTrue(addr("127.0.0.1").isBlockedForImport())     // loopback
        assertTrue(addr("10.0.0.5").isBlockedForImport())      // 10/8
        assertTrue(addr("192.168.1.1").isBlockedForImport())   // 192.168/16
        assertTrue(addr("172.16.5.4").isBlockedForImport())    // 172.16/12
        assertTrue(addr("169.254.169.254").isBlockedForImport()) // link-local / cloud metadata
        assertTrue(addr("100.64.0.1").isBlockedForImport())    // CGNAT 100.64/10
        assertTrue(addr("0.0.0.0").isBlockedForImport())       // wildcard
        assertTrue(addr("::1").isBlockedForImport())           // IPv6 loopback
        assertTrue(addr("fc00::1").isBlockedForImport())       // IPv6 unique-local
        assertTrue(addr("fe80::1").isBlockedForImport())       // IPv6 link-local
    }

    @Test
    fun `allows public addresses`() {
        assertFalse(InetAddress.getByName("8.8.8.8").isBlockedForImport())
        assertFalse(InetAddress.getByName("1.1.1.1").isBlockedForImport())
        assertFalse(InetAddress.getByName("99.64.0.1").isBlockedForImport()) // just below CGNAT range
        assertFalse(InetAddress.getByName("2606:4700:4700::1111").isBlockedForImport()) // public IPv6
    }

    @Test
    fun `charset is read from the content-type header`() {
        assertEquals(Charsets.ISO_8859_1, charsetFromContentType("text/html; charset=ISO-8859-1"))
        assertEquals(StandardCharsets.UTF_8, charsetFromContentType("text/html;charset=utf-8"))
        assertEquals(Charsets.UTF_8, charsetFromContentType("""text/html; charset="UTF-8""""))
        assertNull(charsetFromContentType("text/html")) // no charset → caller defaults to UTF-8
        assertNull(charsetFromContentType("text/html; charset=bogus-enc")) // unsupported
    }

    @Test
    fun `extracts multiple ld+json blocks case-insensitively`() {
        val html = """
            <script type="application/ld+json">{"@type":"Organization"}</script>
            <SCRIPT TYPE='application/ld+json'>{"@type":"Recipe","name":"Zweites"}</SCRIPT>
        """.trimIndent()
        val draft = RecipeImport.fromHtml(html)
        assertNotNull(draft)
        assertEquals("Zweites", draft.title)
    }
}
