package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `POST shopping stores a free-text quantity`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Mehl","quantity":"500 g"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Mehl", body["name"]?.jsonPrimitive?.content)
        assertEquals("500 g", body["quantity"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT shopping sets quantity and note, then clears them with empty strings`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val id = client.post("/api/v1/shopping") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"name":"Eier"}""")
        }.let { Json.parseToJsonElement(it.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content }

        val set = client.put("/api/v1/shopping/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"quantity":"10er","note":"im roten Glas"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        val setBody = Json.parseToJsonElement(set.bodyAsText()).jsonObject
        assertEquals("10er", setBody["quantity"]?.jsonPrimitive?.content)
        assertEquals("im roten Glas", setBody["note"]?.jsonPrimitive?.content)

        // empty string clears; omitted field (name only) leaves them as-is would be null after clear
        val cleared = client.put("/api/v1/shopping/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"quantity":"","note":""}""")
        }
        assertEquals(HttpStatusCode.OK, cleared.status)
        val clearedBody = Json.parseToJsonElement(cleared.bodyAsText()).jsonObject
        // encodeDefaults=false: a null quantity/note is omitted from the payload
        assertNull(clearedBody["quantity"])
        assertNull(clearedBody["note"])
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

    // ---- Batch add (recipe ingredients → list) ----

    private suspend fun ApplicationTestBuilder.batchAdd(token: String, body: String) =
        client.post("/api/v1/shopping/batch") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.itemNames(token: String): List<String> =
        Json.parseToJsonElement(client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText())
            .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun `POST shopping batch formats amount and unit into the item name`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"Mehl","amount":200,"unit":"g"},{"name":"Eier","amount":2}]}""")

        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(2, body["added"]!!.jsonPrimitive.int)
        assertEquals(0, body["merged"]!!.jsonPrimitive.int)
        val names = itemNames(token)
        assertTrue("200 g Mehl" in names)
        assertTrue("2 Eier" in names)
    }

    @Test
    fun `POST shopping batch merges same name and unit by summing`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")
        batchAdd(token, """{"listId":"$listId","items":[{"name":"Mehl","amount":500,"unit":"g"}]}""")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"Mehl","amount":200,"unit":"g"}]}""")

        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(0, body["added"]!!.jsonPrimitive.int)
        assertEquals(1, body["merged"]!!.jsonPrimitive.int)
        val names = itemNames(token)
        assertEquals(1, names.size)
        assertEquals("700 g Mehl", names[0])
    }

    @Test
    fun `POST shopping batch keeps differing units separate`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")

        batchAdd(token, """{"listId":"$listId","items":[{"name":"Zucker","amount":100,"unit":"g"},{"name":"Zucker","amount":1,"unit":"kg"}]}""")

        val names = itemNames(token)
        assertEquals(2, names.size)
        assertTrue("100 g Zucker" in names)
        assertTrue("1 kg Zucker" in names)
    }

    @Test
    fun `POST shopping batch merges duplicate lines within one request`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"Mehl","amount":200,"unit":"g"},{"name":"Mehl","amount":300,"unit":"g"}]}""")

        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(1, body["added"]!!.jsonPrimitive.int)
        assertEquals(1, body["merged"]!!.jsonPrimitive.int)
        val names = itemNames(token)
        assertEquals(1, names.size)
        assertEquals("500 g Mehl", names[0])
    }

    @Test
    fun `POST shopping batch merges multi-word names whose first word is short`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")
        batchAdd(token, """{"listId":"$listId","items":[{"name":"rote Paprika","amount":2}]}""")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"rote Paprika","amount":3}]}""")

        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(0, body["added"]!!.jsonPrimitive.int)
        assertEquals(1, body["merged"]!!.jsonPrimitive.int)
        val names = itemNames(token)
        assertEquals(1, names.size)
        assertEquals("5 rote Paprika", names[0])
    }

    @Test
    fun `POST shopping batch merges multi-word name with unit`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")
        batchAdd(token, """{"listId":"$listId","items":[{"name":"Bio Eier","amount":6,"unit":"stk"}]}""")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"Bio Eier","amount":4,"unit":"stk"}]}""")

        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(0, body["added"]!!.jsonPrimitive.int)
        assertEquals(1, body["merged"]!!.jsonPrimitive.int)
        val names = itemNames(token)
        assertEquals(1, names.size)
        assertEquals("10 stk Bio Eier", names[0])
    }

    @Test
    fun `POST shopping batch keeps a short unknown unit as a separate line`() = testApplication {
        // Only KNOWN_UNITS are re-parsed back out of a label; an unknown short unit ("Glas") stays
        // part of the name, so the same ingredient lands on its own line instead of merging. This is
        // the accepted fallback from issue #47 (priority:low) — pinned so it stays intentional.
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")
        batchAdd(token, """{"listId":"$listId","items":[{"name":"Milch","amount":2,"unit":"Glas"}]}""")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"Milch","amount":1,"unit":"Glas"}]}""")

        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(1, body["added"]!!.jsonPrimitive.int)
        assertEquals(0, body["merged"]!!.jsonPrimitive.int)
        val names = itemNames(token)
        assertEquals(2, names.size)
        assertTrue("2 Glas Milch" in names)
        assertTrue("1 Glas Milch" in names)
    }

    @Test
    fun `POST shopping batch skips an exact duplicate name`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Wocheneinkauf")
        batchAdd(token, """{"listId":"$listId","items":[{"name":"Salz"}]}""")

        val res = batchAdd(token, """{"listId":"$listId","items":[{"name":"Salz"}]}""")

        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(0, body["added"]!!.jsonPrimitive.int)
        assertEquals(1, body["skipped"]!!.jsonPrimitive.int)
        assertEquals(listOf("Salz"), itemNames(token))
    }

    @Test
    fun `POST shopping batch with malformed listId returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = batchAdd(token, """{"listId":"not-a-uuid","items":[{"name":"Mehl"}]}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `POST shopping batch with unknown listId returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = batchAdd(token, """{"listId":"00000000-0000-0000-0000-999999999999","items":[{"name":"Mehl"}]}""")

        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
