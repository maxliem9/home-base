package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeRouteTest {

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

    private suspend fun ApplicationTestBuilder.createRecipe(token: String, body: String) =
        client.post("/api/v1/recipes") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val sampleRecipe = """
        {
          "title": "Pfannkuchen",
          "description": "Klassisch",
          "servings": 2,
          "prepTimeMinutes": 10,
          "cookTimeMinutes": 15,
          "category": "breakfast",
          "ingredients": [
            {"name": "Mehl", "amount": 200, "unit": "g"},
            {"name": "Milch", "amount": 500, "unit": "ml"},
            {"name": "Eier", "amount": 2, "unit": "Stück"}
          ],
          "steps": [
            {"description": "Zutaten verrühren"},
            {"description": "In der Pfanne backen"}
          ]
        }
    """.trimIndent()

    @Test
    fun `GET recipes without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/recipes").status)
    }

    @Test
    fun `GET recipes initially empty`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val response = client.get("/api/v1/recipes") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `POST recipe stores ingredients and steps`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = createRecipe(token, sampleRecipe)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Pfannkuchen", body["title"]?.jsonPrimitive?.content)
        assertEquals("BREAKFAST", body["category"]?.jsonPrimitive?.content)
        assertEquals(2, body["servings"]?.jsonPrimitive?.int)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)
        assertEquals(3, body["ingredients"]!!.jsonArray.size)
        assertEquals(2, body["steps"]!!.jsonArray.size)
        // step numbers are assigned 1-based by list position
        assertEquals(1, body["steps"]!!.jsonArray[0].jsonObject["stepNumber"]?.jsonPrimitive?.int)
    }

    @Test
    fun `POST recipe with blank title returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val response = createRecipe(token, """{"title":"   ","category":"DINNER"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST recipe with invalid category returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val response = createRecipe(token, """{"title":"X","category":"BRUNCH"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET detail scales ingredient amounts by servings`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val id = Json.parseToJsonElement(createRecipe(token, sampleRecipe).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        // base recipe is 2 servings; request 4 → amounts double
        val response = client.get("/api/v1/recipes/$id?servings=4") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(4, body["servings"]?.jsonPrimitive?.int)
        val mehl = body["ingredients"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["name"]?.jsonPrimitive?.content == "Mehl" }
        assertEquals(400.0, mehl["amount"]?.jsonPrimitive?.double)
    }

    @Test
    fun `GET list filters by category`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        createRecipe(token, """{"title":"Müsli","category":"BREAKFAST"}""")
        createRecipe(token, """{"title":"Lasagne","category":"DINNER"}""")

        val dinner = Json.parseToJsonElement(
            client.get("/api/v1/recipes?category=DINNER") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, dinner.size)
        assertEquals("Lasagne", dinner[0].jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT replaces ingredients and steps`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val id = Json.parseToJsonElement(createRecipe(token, sampleRecipe).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/recipes/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Crêpes","ingredients":[{"name":"Mehl","amount":150,"unit":"g"}],"steps":[{"description":"Rühren"}]}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals("Crêpes", body["title"]?.jsonPrimitive?.content)
        assertEquals(1, body["ingredients"]!!.jsonArray.size)
        assertEquals(1, body["steps"]!!.jsonArray.size)
    }

    @Test
    fun `PUT unknown recipe returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val response = client.put("/api/v1/recipes/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Ghost"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE removes recipe and its children`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val id = Json.parseToJsonElement(createRecipe(token, sampleRecipe).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/recipes/$id") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/recipes/$id") { bearerAuth(token) }.status)
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/recipes") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
    }
}
