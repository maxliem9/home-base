package com.homebase

import com.homebase.shopping.GroceryCatalog
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-unit coverage of the grocery catalog: normalization + name → category/icon resolution. */
class GroceryCatalogTest {

    @Test
    fun `resolves a known staple to its category and emoji`() {
        val r = GroceryCatalog.resolve("Milch")
        assertEquals("DAIRY", r.category)
        assertEquals("🥛", r.icon)
    }

    @Test
    fun `strips a leading quantity and unit before matching`() {
        assertEquals("PRODUCE", GroceryCatalog.resolve("2 Paprika").category)
        assertEquals("PANTRY", GroceryCatalog.resolve("500 g Mehl").category)
        assertEquals("mehl", GroceryCatalog.normalize("500 g Mehl"))
        assertEquals("paprika", GroceryCatalog.normalize("2 Paprika"))
    }

    @Test
    fun `substring fallback handles a prefixed or pluralised name`() {
        assertEquals("PRODUCE", GroceryCatalog.resolve("Bio Tomaten").category)
        assertEquals("DAIRY", GroceryCatalog.resolve("Hafermilch").category)
    }

    @Test
    fun `unknown name falls back to OTHER with the cart icon`() {
        val r = GroceryCatalog.resolve("Zaubertrank 3000")
        assertEquals(GroceryCatalog.OTHER, r.category)
        assertEquals(GroceryCatalog.DEFAULT_ICON, r.icon)
    }

    @Test
    fun `category set is the fixed ten in route order`() {
        assertEquals(10, GroceryCatalog.categories.size)
        assertEquals("PRODUCE", GroceryCatalog.categories.first().key)
        assertEquals(GroceryCatalog.OTHER, GroceryCatalog.categories.last().key)
        assertTrue(GroceryCatalog.isValidCategory("DAIRY"))
        assertFalse(GroceryCatalog.isValidCategory("BOGUS"))
    }
}

/** Route-level coverage of categorization, usage stats / suggestions, and manual overrides. */
class ShoppingCategoryRouteTest {

    private suspend fun ApplicationTestBuilder.token(): String {
        val res = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"password123"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.addItem(token: String, name: String): JsonObject {
        val res = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":${JsonPrimitive(name)}}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.suggestions(token: String, q: String? = null): JsonArray {
        val url = "/api/v1/shopping/suggestions" + (q?.let { "?q=$it" } ?: "")
        return Json.parseToJsonElement(client.get(url) { bearerAuth(token) }.bodyAsText()).jsonArray
    }

    @Test
    fun `POST resolves category and icon from the catalog`() = testApplication {
        configureTestApplication()
        val token = token()

        val milch = addItem(token, "Milch")
        assertEquals("DAIRY", milch["category"]?.jsonPrimitive?.content)
        assertEquals("🥛", milch["icon"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST of an unknown item lands in OTHER`() = testApplication {
        configureTestApplication()
        val token = token()

        val item = addItem(token, "Zaubertrank 3000")
        assertEquals("OTHER", item["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `suggestions expose the catalog baseline before any usage`() = testApplication {
        configureTestApplication()
        val token = token()

        val all = suggestions(token)
        assertTrue(all.isNotEmpty(), "catalog baseline should be non-empty on day one")
        val milch = all.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == "Milch" }
        assertTrue(milch != null, "Milch should be in the baseline suggestions")
        assertEquals("DAIRY", milch!!["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `usage count climbs and ranks the suggestion to the top`() = testApplication {
        configureTestApplication()
        val token = token()
        addItem(token, "Milch")
        addItem(token, "Milch")

        val matches = suggestions(token, q = "milch").map { it.jsonObject }
        val first = matches.first()
        assertEquals("Milch", first["name"]?.jsonPrimitive?.content)
        assertEquals(2, first["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `PUT override moves the item and is remembered for the next add`() = testApplication {
        configureTestApplication()
        val token = token()

        val pizza = addItem(token, "Pizza")
        assertEquals("FROZEN", pizza["category"]?.jsonPrimitive?.content)
        val id = pizza["id"]!!.jsonPrimitive.content

        val moved = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"category":"PANTRY"}""")
        }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals("PANTRY", Json.parseToJsonElement(moved.bodyAsText()).jsonObject["category"]?.jsonPrimitive?.content)

        // a fresh add of the same name now inherits the remembered category
        val pizza2 = addItem(token, "Pizza")
        assertEquals("PANTRY", pizza2["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT with an unknown category is rejected`() = testApplication {
        configureTestApplication()
        val token = token()
        val id = addItem(token, "Brot")["id"]!!.jsonPrimitive.content

        val res = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"category":"NOT_A_CATEGORY"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `item whose name normalizes to blank is still created`() = testApplication {
        // "+++" has no usable stats key; resolveForItem/recordUsages must skip it gracefully, not 500.
        configureTestApplication()
        val token = token()

        val item = addItem(token, "+++")
        assertEquals("+++", item["name"]?.jsonPrimitive?.content)
        assertEquals("OTHER", item["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `batch add categorizes each created item`() = testApplication {
        configureTestApplication()
        val token = token()
        val listId = Json.parseToJsonElement(
            client.post("/api/v1/shopping/lists") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Wocheneinkauf"}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/v1/shopping/batch") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"listId":"$listId","items":[{"name":"Mehl","amount":500,"unit":"g"},{"name":"Tomaten","amount":3}]}""")
        }

        val byName = Json.parseToJsonElement(client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText())
            .jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["category"]?.jsonPrimitive?.content }
        assertEquals("PANTRY", byName["500 g Mehl"])
        assertEquals("PRODUCE", byName["3 Tomaten"])
    }

    // ---- Editable category catalog (#411) ----

    private suspend fun ApplicationTestBuilder.categories(token: String): JsonArray =
        Json.parseToJsonElement(client.get("/api/v1/shopping/categories") { bearerAuth(token) }.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.createCategory(token: String, label: String, emoji: String): JsonObject =
        Json.parseToJsonElement(
            client.post("/api/v1/shopping/categories") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"label":${JsonPrimitive(label)},"emoji":${JsonPrimitive(emoji)}}""")
            }.bodyAsText()
        ).jsonObject

    private suspend fun ApplicationTestBuilder.itemById(token: String, id: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText())
            .jsonArray.map { it.jsonObject }.first { it["id"]?.jsonPrimitive?.content == id }

    @Test
    fun `GET categories returns the seeded builtin catalog in route order`() = testApplication {
        configureTestApplication()
        val token = token()
        val cats = categories(token).map { it.jsonObject }
        assertEquals(10, cats.size)
        assertEquals("PRODUCE", cats.first()["key"]?.jsonPrimitive?.content)
        assertEquals("OTHER", cats.last()["key"]?.jsonPrimitive?.content)
        assertTrue(cats.all { it["isBuiltin"]?.jsonPrimitive?.boolean == true })
    }

    @Test
    fun `POST creates a custom category usable as an item override`() = testApplication {
        configureTestApplication()
        val token = token()
        val created = createCategory(token, "Drogerie", "🧴")
        val key = created["key"]!!.jsonPrimitive.content
        assertEquals("DROGERIE", key)
        assertEquals(false, created["isBuiltin"]?.jsonPrimitive?.boolean)

        // the new key is a valid override target (validation now reads the DB catalog)
        val id = addItem(token, "Wattestäbchen")["id"]!!.jsonPrimitive.content
        val moved = client.put("/api/v1/shopping/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"category":"$key"}""")
        }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals(key, Json.parseToJsonElement(moved.bodyAsText()).jsonObject["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT edits a builtin category's label and emoji`() = testApplication {
        configureTestApplication()
        val token = token()
        val res = client.put("/api/v1/shopping/categories/DAIRY") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"label":"Molkerei","emoji":"🐄"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val dto = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("Molkerei", dto["label"]?.jsonPrimitive?.content)
        assertEquals("🐄", dto["emoji"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE a custom category reassigns its items to OTHER`() = testApplication {
        configureTestApplication()
        val token = token()
        val key = createCategory(token, "Baumarkt", "🔧")["key"]!!.jsonPrimitive.content

        val id = addItem(token, "Schrauben")["id"]!!.jsonPrimitive.content
        client.put("/api/v1/shopping/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"category":"$key"}""")
        }

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/categories/$key") { bearerAuth(token) }.status)
        assertTrue(categories(token).none { it.jsonObject["key"]?.jsonPrimitive?.content == key })
        assertEquals("OTHER", itemById(token, id)["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE a builtin category reassigns items and stops resolving to it`() = testApplication {
        configureTestApplication()
        val token = token()
        val pizza = addItem(token, "Pizza")
        assertEquals("FROZEN", pizza["category"]?.jsonPrimitive?.content)
        val pizzaId = pizza["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/categories/FROZEN") { bearerAuth(token) }.status)

        // existing item reassigned …
        assertEquals("OTHER", itemById(token, pizzaId)["category"]?.jsonPrimitive?.content)
        // … and a fresh add no longer lands in the deleted category (resolve guarded → OTHER)
        assertEquals("OTHER", addItem(token, "Tiefkühlpizza")["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE the OTHER fallback is rejected`() = testApplication {
        configureTestApplication()
        val token = token()
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/v1/shopping/categories/OTHER") { bearerAuth(token) }.status)
        assertTrue(categories(token).any { it.jsonObject["key"]?.jsonPrimitive?.content == "OTHER" })
    }
}
