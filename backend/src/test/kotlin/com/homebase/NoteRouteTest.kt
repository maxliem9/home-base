package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteRouteTest {

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

    private suspend fun ApplicationTestBuilder.createNote(token: String, body: String) =
        client.post("/api/v1/notes") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `GET notes without token returns 401`() = testApplication {
        configureTestApplication()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/notes").status)
    }

    @Test
    fun `GET notes with token returns empty list initially`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.get("/api/v1/notes") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `POST note defaults to shared visibility and empty content`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = createNote(token, """{"title":"Einkaufsideen"}""")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Einkaufsideen", body["title"]?.jsonPrimitive?.content)
        assertEquals("", body["content"]?.jsonPrimitive?.content)
        assertEquals("SHARED", body["visibility"]?.jsonPrimitive?.content)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)
        assertTrue(body["tags"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `POST note stores content tags and visibility`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = createNote(
            token,
            """{"title":"Rezept","content":"# Pasta\n- Nudeln","tags":["essen","abend"],"visibility":"PRIVATE"}""",
        )

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("PRIVATE", body["visibility"]?.jsonPrimitive?.content)
        assertEquals("# Pasta\n- Nudeln", body["content"]?.jsonPrimitive?.content)
        val tags = body["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("essen", "abend"), tags)
    }

    @Test
    fun `POST note with blank title returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = createNote(token, """{"title":"   "}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST note with invalid visibility returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = createNote(token, """{"title":"X","visibility":"SECRET"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT note updates fields and is returned`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val id = Json.parseToJsonElement(
            createNote(token, """{"title":"Alt"}""").bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/notes/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Neu","content":"Inhalt","tags":["a"]}""")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals("Neu", body["title"]?.jsonPrimitive?.content)
        assertEquals("Inhalt", body["content"]?.jsonPrimitive?.content)
        assertEquals(listOf("a"), body["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `PUT unknown note returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.put("/api/v1/notes/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Ghost"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE note removes it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val id = Json.parseToJsonElement(
            createNote(token, """{"title":"Weg damit"}""").bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/notes/$id") { bearerAuth(token) }.status)
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/notes") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
    }

    @Test
    fun `shared notes are visible to both users`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")

        createNote(alice, """{"title":"Geteilt","visibility":"SHARED"}""")

        val bobNotes = Json.parseToJsonElement(
            client.get("/api/v1/notes") { bearerAuth(bob) }.bodyAsText()
        ).jsonArray
        assertEquals(1, bobNotes.size)
        assertEquals("Geteilt", bobNotes[0].jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `private notes are hidden from the other user`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")

        createNote(alice, """{"title":"Geheim","visibility":"PRIVATE"}""")

        // bob sees nothing
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/notes") { bearerAuth(bob) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
        // alice sees her own private note
        assertEquals(
            1,
            Json.parseToJsonElement(
                client.get("/api/v1/notes") { bearerAuth(alice) }.bodyAsText()
            ).jsonArray.size,
        )
    }

    @Test
    fun `other user cannot update a private note`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")

        val id = Json.parseToJsonElement(
            createNote(alice, """{"title":"Geheim","visibility":"PRIVATE"}""").bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/v1/notes/$id") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Hacked"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `search filters notes by query across title content and tags`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        createNote(token, """{"title":"Pasta Rezept","content":"Nudeln kochen","tags":["essen"]}""")
        createNote(token, """{"title":"Steuer","content":"Belege sortieren","tags":["admin"]}""")

        // matches title
        val byTitle = Json.parseToJsonElement(
            client.get("/api/v1/notes?q=pasta") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, byTitle.size)

        // matches content (case-insensitive)
        val byContent = Json.parseToJsonElement(
            client.get("/api/v1/notes?q=BELEGE") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, byContent.size)

        // matches tag
        val byTag = Json.parseToJsonElement(
            client.get("/api/v1/notes?q=essen") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, byTag.size)

        // no match
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/notes?q=xyz") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
    }
}
