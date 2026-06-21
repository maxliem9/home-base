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
}
