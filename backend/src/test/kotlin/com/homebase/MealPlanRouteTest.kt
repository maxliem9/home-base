package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MealPlanRouteTest {

    private suspend fun ApplicationTestBuilder.loginAndGetToken(
        username: String = "alice",
        password: String = "password123",
    ): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    /** Creates a recipe and returns its id — meal-plan entries reference a real recipe. */
    private suspend fun ApplicationTestBuilder.createRecipe(token: String, title: String, category: String = "DINNER"): String {
        val res = client.post("/api/v1/recipes") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"$title","category":"$category"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.range(token: String, from: String, to: String): JsonArray =
        Json.parseToJsonElement(client.get("/api/v1/meal-plan?from=$from&to=$to") { bearerAuth(token) }.bodyAsText()).jsonArray

    @Test
    fun `GET meal-plan without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/meal-plan?from=2026-06-15&to=2026-06-21").status)
    }

    @Test
    fun `GET without from or to returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/meal-plan") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/meal-plan?from=2026-06-15") { bearerAuth(token) }.status)
    }

    @Test
    fun `GET with from after to returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/meal-plan?from=2026-06-21&to=2026-06-15") { bearerAuth(token) }.status)
    }

    @Test
    fun `GET with an oversized range returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // > MAX_RANGE_DAYS (370): ~two years is well over the cap.
        val res = client.get("/api/v1/meal-plan?from=2026-01-01&to=2027-12-31") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PUT sets an entry and GET returns it with recipe title and category`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne", "DINNER")

        val put = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val entries = range(token, "2026-06-15", "2026-06-21")
        assertEquals(1, entries.size)
        val e = entries[0].jsonObject
        assertEquals("DINNER", e["slot"]?.jsonPrimitive?.content)
        assertEquals("2026-06-15", e["date"]?.jsonPrimitive?.content)
        assertEquals(recipeId, e["recipeId"]?.jsonPrimitive?.content)
        assertEquals("Lasagne", e["recipeTitle"]?.jsonPrimitive?.content)
        assertEquals("DINNER", e["recipeCategory"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT stores servings and GET returns them, omitted servings stays null`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")

        client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId","servings":4}""")
        }
        val withServings = range(token, "2026-06-15", "2026-06-21")[0].jsonObject
        assertEquals(4, withServings["servings"]?.jsonPrimitive?.int)

        // Omitted servings → null, which encodeDefaults=false drops from the payload.
        client.put("/api/v1/meal-plan/2026-06-16/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId"}""")
        }
        val noServings = range(token, "2026-06-15", "2026-06-21").map { it.jsonObject }
            .first { it["date"]?.jsonPrimitive?.content == "2026-06-16" }
        assertTrue(noServings["servings"].let { it == null || it is JsonNull })
    }

    @Test
    fun `PUT with servings below 1 returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")
        val res = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId","servings":0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PUT on an occupied slot replaces the recipe`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val first = createRecipe(token, "Lasagne")
        val second = createRecipe(token, "Pizza")

        suspend fun setSlot(id: String) = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$id"}""")
        }
        setSlot(first)
        setSlot(second)

        val entries = range(token, "2026-06-15", "2026-06-21")
        assertEquals(1, entries.size)
        assertEquals(second, entries[0].jsonObject["recipeId"]?.jsonPrimitive?.content)
        assertEquals("Pizza", entries[0].jsonObject["recipeTitle"]?.jsonPrimitive?.content)
    }

    @Test
    fun `lowercase slot in the path is accepted and normalised`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Müsli", "BREAKFAST")
        val put = client.put("/api/v1/meal-plan/2026-06-15/breakfast") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals("BREAKFAST", range(token, "2026-06-15", "2026-06-21")[0].jsonObject["slot"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT with a free-text dishTitle sets an entry without a recipe`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val put = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"dishTitle":"Pizza bestellen"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val e = range(token, "2026-06-15", "2026-06-21")[0].jsonObject
        assertEquals("Pizza bestellen", e["dishTitle"]?.jsonPrimitive?.content)
        // no recipe: encodeDefaults=false drops the recipe fields entirely
        assertTrue(e["recipeId"].let { it == null || it is JsonNull })
        assertTrue(e["recipeTitle"].let { it == null || it is JsonNull })
    }

    @Test
    fun `a free-text entry replaces a recipe in the same slot and vice versa`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")

        suspend fun put(body: String) = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        put("""{"recipeId":"$recipeId"}""")
        put("""{"dishTitle":"Reste essen"}""") // free text replaces the recipe

        val afterText = range(token, "2026-06-15", "2026-06-21")
        assertEquals(1, afterText.size)
        assertEquals("Reste essen", afterText[0].jsonObject["dishTitle"]?.jsonPrimitive?.content)
        assertTrue(afterText[0].jsonObject["recipeId"].let { it == null || it is JsonNull })

        put("""{"recipeId":"$recipeId"}""") // recipe replaces the free text again
        val afterRecipe = range(token, "2026-06-15", "2026-06-21")
        assertEquals(1, afterRecipe.size)
        assertEquals(recipeId, afterRecipe[0].jsonObject["recipeId"]?.jsonPrimitive?.content)
        assertTrue(afterRecipe[0].jsonObject["dishTitle"].let { it == null || it is JsonNull })
    }

    @Test
    fun `PUT with neither recipeId nor dishTitle returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        // a blank dishTitle counts as "not provided" too
        val blank = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"dishTitle":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
    }

    @Test
    fun `PUT with both recipeId and dishTitle returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")
        val res = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId","dishTitle":"Pizza"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PUT with an unknown recipe returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"00000000-0000-0000-0000-0000000000ff"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `PUT with an invalid slot or date returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")
        val body = """{"recipeId":"$recipeId"}"""
        val badSlot = client.put("/api/v1/meal-plan/2026-06-15/SUPPER") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, badSlot.status)
        val badDate = client.put("/api/v1/meal-plan/15-06-2026/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, badDate.status)
    }

    @Test
    fun `DELETE clears a slot`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")
        client.put("/api/v1/meal-plan/2026-06-15/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId"}""")
        }
        val del = client.delete("/api/v1/meal-plan/2026-06-15/DINNER") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertTrue(range(token, "2026-06-15", "2026-06-21").isEmpty())

        // Idempotent: deleting an already-empty slot still answers 204.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/meal-plan/2026-06-15/DINNER") { bearerAuth(token) }.status)
    }

    @Test
    fun `GET range only returns entries within the range`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token, "Lasagne")
        suspend fun plan(date: String) = client.put("/api/v1/meal-plan/$date/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId"}""")
        }
        plan("2026-06-15") // inside
        plan("2026-06-22") // outside (next week)

        val entries = range(token, "2026-06-15", "2026-06-21")
        assertEquals(1, entries.size)
        assertEquals("2026-06-15", entries[0].jsonObject["date"]?.jsonPrimitive?.content)
    }
}
