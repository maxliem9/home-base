package com.homebase

import com.homebase.model.ShoppingTemplateDto
import com.homebase.model.ShoppingTemplateItemDto
import com.homebase.plugins.appJson
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Route + DTO coverage for the named shopping templates (#215): a create→get→update→delete
 * round-trip, embedded items ordered by sortOrder, and the realtime broadcasts on the shared
 * `/ws/shopping` channel. Plus a unit-level check that the template DTO honours encodeDefaults=false
 * (empty items omitted) and that the broadcast frame is the compact appJson form.
 */
class ShoppingTemplateRouteTest {

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

    private suspend fun ApplicationTestBuilder.createTemplate(token: String, body: String) =
        client.post("/api/v1/shopping/templates") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `GET templates without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/shopping/templates").status)
    }

    @Test
    fun `GET templates initially empty`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val response = client.get("/api/v1/shopping/templates") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `POST template creates it with embedded items ordered by sortOrder`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = createTemplate(
            token,
            """{"name":"Wocheneinkauf","items":[{"name":"Milch"},{"name":"Brot"},{"name":"Eier"}]}""",
        )

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Wocheneinkauf", body["name"]?.jsonPrimitive?.content)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)
        val items = body["items"]!!.jsonArray
        assertEquals(listOf("Milch", "Brot", "Eier"), items.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        // sortOrder reflects input order (0,1,2)
        assertEquals(listOf(0, 1, 2), items.map { it.jsonObject["sortOrder"]!!.jsonPrimitive.int })
    }

    @Test
    fun `POST template with blank name returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, createTemplate(token, """{"name":"   "}""").status)
    }

    @Test
    fun `POST template drops blank item names`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val body = Json.parseToJsonElement(
            createTemplate(token, """{"name":"T","items":[{"name":"Milch"},{"name":"  "},{"name":"Brot"}]}""").bodyAsText(),
        ).jsonObject
        val names = body["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Milch", "Brot"), names)
        // sort_order is dense over the surviving items (0,1), not (0,2)
        assertEquals(listOf(0, 1), body["items"]!!.jsonArray.map { it.jsonObject["sortOrder"]!!.jsonPrimitive.int })
    }

    @Test
    fun `GET returns created templates with their items`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        createTemplate(token, """{"name":"A","items":[{"name":"x"}]}""")
        createTemplate(token, """{"name":"B","items":[]}""")

        val list = Json.parseToJsonElement(
            client.get("/api/v1/shopping/templates") { bearerAuth(token) }.bodyAsText(),
        ).jsonArray
        assertEquals(2, list.size)
        val a = list.first { it.jsonObject["name"]!!.jsonPrimitive.content == "A" }.jsonObject
        assertEquals(listOf("x"), a["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `PUT replaces name and items wholesale`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            createTemplate(token, """{"name":"Alt","items":[{"name":"Alt1"},{"name":"Alt2"}]}""").bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content

        val res = client.put("/api/v1/shopping/templates/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Neu","items":[{"name":"Neu1"}]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("Neu", body["name"]?.jsonPrimitive?.content)
        assertEquals(listOf("Neu1"), body["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `PUT name only leaves items untouched`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            createTemplate(token, """{"name":"Alt","items":[{"name":"keep"}]}""").bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content

        val body = Json.parseToJsonElement(
            client.put("/api/v1/shopping/templates/$id") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Umbenannt"}""")
            }.bodyAsText(),
        ).jsonObject
        assertEquals("Umbenannt", body["name"]?.jsonPrimitive?.content)
        // items omitted in the request → unchanged (not wiped)
        assertEquals(listOf("keep"), body["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `PUT with empty items array clears them`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            createTemplate(token, """{"name":"T","items":[{"name":"weg"}]}""").bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content

        val body = Json.parseToJsonElement(
            client.put("/api/v1/shopping/templates/$id") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"items":[]}""")
            }.bodyAsText(),
        ).jsonObject
        // empty list provided → items replaced with nothing; encodeDefaults=false omits the now-empty array
        val items = body["items"]
        assertTrue(items == null || items.jsonArray.isEmpty())
    }

    @Test
    fun `PUT unknown template returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/shopping/templates/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Ghost"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `PUT with blank name returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            createTemplate(token, """{"name":"T"}""").bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content
        val res = client.put("/api/v1/shopping/templates/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `DELETE removes the template and cascades its items`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            createTemplate(token, """{"name":"T","items":[{"name":"x"},{"name":"y"}]}""").bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/shopping/templates/$id") { bearerAuth(token) }.status,
        )
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/shopping/templates") { bearerAuth(token) }.bodyAsText(),
            ).jsonArray.isEmpty(),
        )
    }

    @Test
    fun `DELETE unknown template returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/v1/shopping/templates/00000000-0000-0000-0000-999999999999") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `templates are shared - both users see all of them`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        createTemplate(alice, """{"name":"Alices Vorlage"}""")

        // Bob may read it, update it, and delete it (no ownership check).
        val list = Json.parseToJsonElement(
            client.get("/api/v1/shopping/templates") { bearerAuth(bob) }.bodyAsText(),
        ).jsonArray
        assertEquals(1, list.size)
        val id = list[0].jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/v1/shopping/templates/$id") {
                bearerAuth(bob)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Von Bob umbenannt"}""")
            }.status,
        )
    }

    // ---- Realtime broadcasts on the shared /ws/shopping channel ----

    private suspend fun DefaultClientWebSocketSession.nextMessage(): JsonObject {
        val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
        return Json.parseToJsonElement(frame.readText()).jsonObject
    }

    @Test
    fun `template create update delete broadcast on the shopping channel`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/api/v1/ws/shopping?token=$bob") {
            delay(300)

            val created = createTemplate(alice, """{"name":"Vorlage","items":[{"name":"Milch"}]}""")
            val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

            val createdMsg = nextMessage()
            assertEquals("SHOPPING_TEMPLATE_CREATED", createdMsg["type"]?.jsonPrimitive?.content)
            assertEquals("Vorlage", createdMsg["payload"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertEquals(
                listOf("Milch"),
                createdMsg["payload"]?.jsonObject?.get("items")?.jsonArray
                    ?.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            )

            client.put("/api/v1/shopping/templates/$id") {
                bearerAuth(alice)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Geändert"}""")
            }
            val updatedMsg = nextMessage()
            assertEquals("SHOPPING_TEMPLATE_UPDATED", updatedMsg["type"]?.jsonPrimitive?.content)
            assertEquals("Geändert", updatedMsg["payload"]?.jsonObject?.get("name")?.jsonPrimitive?.content)

            client.delete("/api/v1/shopping/templates/$id") { bearerAuth(alice) }
            val deletedMsg = nextMessage()
            assertEquals("SHOPPING_TEMPLATE_DELETED", deletedMsg["type"]?.jsonPrimitive?.content)
            assertEquals(id, deletedMsg["payload"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        }
    }

    // ---- encodeDefaults=false (#46) at the DTO level ----

    @Test
    fun `ShoppingTemplateDto omits empty items via appJson (encodeDefaults=false)`() {
        val empty = ShoppingTemplateDto(
            id = "t1",
            name = "Leer",
            // items = emptyList() default — must be omitted
            createdBy = "alice",
            createdAt = "2026-06-15T10:00:00Z",
        )
        val json = appJson.encodeToString(ShoppingTemplateDto.serializer(), empty)
        assertFalse(json.contains("\"items\""), "empty items must be omitted, was: $json")

        // Positive control: a populated items list IS encoded (so the test can't pass trivially).
        val filled = empty.copy(items = listOf(ShoppingTemplateItemDto(id = "i1", name = "Milch", sortOrder = 0)))
        val filledJson = appJson.encodeToString(ShoppingTemplateDto.serializer(), filled)
        assertTrue(filledJson.contains("\"items\""), "populated items must be encoded, was: $filledJson")
    }
}
