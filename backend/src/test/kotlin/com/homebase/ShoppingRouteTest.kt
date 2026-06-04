package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShoppingRouteTest {

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

    @Test
    fun `GET shopping without token returns 401`() = testApplication {
        configureTestApplication()

        val response = client.get("/api/v1/shopping")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET shopping with token returns empty list initially`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.get("/api/v1/shopping") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `POST shopping creates unchecked item`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Milch"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Milch", body["name"]?.jsonPrimitive?.content)
        assertFalse(body["checked"]!!.jsonPrimitive.boolean)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST shopping with blank name returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private suspend fun ApplicationTestBuilder.createList(token: String, name: String): String {
        val res = client.post("/api/v1/shopping/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `POST shopping with listId stores it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")

        val response = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Äpfel","listId":"$listId"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Äpfel", body["name"]?.jsonPrimitive?.content)
        assertEquals(listId, body["listId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST shopping with unknown listId returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X","listId":"00000000-0000-0000-0000-999999999999"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT shopping moves item between lists and can clear`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Drogerie")

        val created = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Brot"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assigned = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Vollkornbrot","listId":"$listId"}""")
        }
        assertEquals(HttpStatusCode.OK, assigned.status)
        val body = Json.parseToJsonElement(assigned.bodyAsText()).jsonObject
        assertEquals("Vollkornbrot", body["name"]?.jsonPrimitive?.content)
        assertEquals(listId, body["listId"]?.jsonPrimitive?.content)

        val cleared = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"listId":""}""")
        }
        val clearedList = Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["listId"]
        assertTrue(clearedList == null || clearedList is JsonNull)
    }

    @Test
    fun `DELETE list removes its items`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Baumarkt")
        client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Schrauben","listId":"$listId"}""")
        }

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/shopping/lists/$listId") { bearerAuth(token) }.status,
        )
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/shopping/lists") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
    }

    @Test
    fun `PUT shopping checking item sets checkedAt`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Butter"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"checked":true}""")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
        assertTrue(body["checkedAt"]?.jsonPrimitive?.content?.isNotBlank() == true)
    }

    @Test
    fun `PUT shopping unchecking item clears checkedAt`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Käse"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"checked":true}""")
        }
        val unchecked = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"checked":false}""")
        }

        val body = Json.parseToJsonElement(unchecked.bodyAsText()).jsonObject
        assertFalse(body["checked"]!!.jsonPrimitive.boolean)
        // checkedAt cleared: absent or explicit null, but never a leftover timestamp
        val checkedAt = body["checkedAt"]
        assertTrue(checkedAt == null || checkedAt is JsonNull)
    }

    @Test
    fun `PUT unknown shopping item returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.put("/api/v1/shopping/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Ghost"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE shopping removes item from list`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Eier"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/$id") { bearerAuth(token) }.status)
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
    }

    @Test
    fun `DELETE unknown shopping item returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.delete("/api/v1/shopping/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET shopping returns items from both users`() = testApplication {
        configureTestApplication()
        val aliceToken = loginAndGetToken("alice", "password123")
        val bobToken = loginAndGetToken("bob", "password456")

        client.post("/api/v1/shopping") {
            bearerAuth(aliceToken)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice's item"}""")
        }
        client.post("/api/v1/shopping") {
            bearerAuth(bobToken)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Bob's item"}""")
        }

        val items = Json.parseToJsonElement(
            client.get("/api/v1/shopping") { bearerAuth(aliceToken) }.bodyAsText()
        ).jsonArray
        assertEquals(2, items.size)
    }
}
