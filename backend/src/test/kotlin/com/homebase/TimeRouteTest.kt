package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeRouteTest {

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

    private suspend fun ApplicationTestBuilder.createProject(
        token: String,
        name: String = "Arbeit",
        color: String = "#4F46E5",
    ): String {
        val res = client.post("/api/v1/time/projects") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","color":"$color"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `GET projects without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/time/projects").status)
    }

    @Test
    fun `POST project creates it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/time/projects") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Garten","color":"#10B981"}""")
        }

        assertEquals(HttpStatusCode.Created, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("Garten", body["name"]?.jsonPrimitive?.content)
        assertEquals("#10B981", body["color"]?.jsonPrimitive?.content)
        assertEquals(false, body["archived"]?.jsonPrimitive?.boolean)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST project with bad color returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.post("/api/v1/time/projects") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X","color":"blau"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PATCH archive hides nothing but flips flag`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = createProject(token)

        val res = client.patch("/api/v1/time/projects/$id/archive") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(true, Json.parseToJsonElement(res.bodyAsText()).jsonObject["archived"]?.jsonPrimitive?.boolean)

        // still listed
        val list = Json.parseToJsonElement(
            client.get("/api/v1/time/projects") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, list.size)
    }

    @Test
    fun `start timer creates a running entry`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)

        val res = client.post("/api/v1/time/entries/start") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","description":"Coden"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(projectId, body["projectId"]?.jsonPrimitive?.content)
        assertTrue(body["stoppedAt"] == null || body["stoppedAt"] is JsonNull)
    }

    @Test
    fun `starting a second timer stops the first`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)

        val first = Json.parseToJsonElement(client.post("/api/v1/time/entries/start") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/v1/time/entries/start") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId"}""")
        }

        // exactly one running timer remains
        val entries = Json.parseToJsonElement(
            client.get("/api/v1/time/entries") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(2, entries.size)
        val running = entries.filter { it.jsonObject["stoppedAt"].let { s -> s == null || s is JsonNull } }
        assertEquals(1, running.size)
        // and the first one is now stopped
        val firstEntry = entries.first { it.jsonObject["id"]!!.jsonPrimitive.content == first }
        assertTrue(firstEntry.jsonObject["stoppedAt"]?.jsonPrimitive?.content?.isNotBlank() == true)
    }

    @Test
    fun `GET running returns the live timer then 404 after stop`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)

        client.post("/api/v1/time/entries/start") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId"}""")
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/time/running") { bearerAuth(token) }.status)

        val stopped = client.post("/api/v1/time/entries/stop") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, stopped.status)
        val durationSeconds = Json.parseToJsonElement(stopped.bodyAsText()).jsonObject["durationSeconds"]
        assertTrue(durationSeconds != null && durationSeconds !is JsonNull)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/time/running") { bearerAuth(token) }.status)
    }

    @Test
    fun `stop without running timer returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/time/entries/stop") { bearerAuth(token) }.status)
    }

    @Test
    fun `manual entry computes duration`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)

        val res = client.post("/api/v1/time/entries") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"projectId":"$projectId","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:30:00Z","description":"Meeting"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(5400L, body["durationSeconds"]?.jsonPrimitive?.long)
    }

    @Test
    fun `manual entry with stop before start returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)

        val res = client.post("/api/v1/time/entries") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"projectId":"$projectId","startedAt":"2026-06-03T09:00:00Z","stoppedAt":"2026-06-03T08:00:00Z"}"""
            )
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `entries can be filtered by project_id`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectA = createProject(token, "A", "#111111")
        val projectB = createProject(token, "B", "#222222")

        client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectA","startedAt":"2026-06-01T08:00:00Z","stoppedAt":"2026-06-01T09:00:00Z"}""")
        }
        client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectB","startedAt":"2026-06-01T10:00:00Z","stoppedAt":"2026-06-01T11:00:00Z"}""")
        }

        val onlyA = Json.parseToJsonElement(
            client.get("/api/v1/time/entries?project_id=$projectA") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, onlyA.size)
        assertEquals(projectA, onlyA[0].jsonObject["projectId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT entry updates description and DELETE removes it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)
        val id = Json.parseToJsonElement(client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:00:00Z"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/time/entries/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"description":"Aktualisiert"}""")
        }
        assertEquals("Aktualisiert", Json.parseToJsonElement(updated.bodyAsText()).jsonObject["description"]?.jsonPrimitive?.content)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/time/entries/$id") { bearerAuth(token) }.status)
        assertTrue(
            Json.parseToJsonElement(client.get("/api/v1/time/entries") { bearerAuth(token) }.bodyAsText()).jsonArray.isEmpty()
        )
    }

    @Test
    fun `start with unknown project returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.post("/api/v1/time/entries/start") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"00000000-0000-0000-0000-000000000099"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `running timers are tracked per user independently`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val projectId = createProject(alice)

        client.post("/api/v1/time/entries/start") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId"}""")
        }
        // bob has no running timer even though alice does
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/time/running") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/time/running") { bearerAuth(alice) }.status)
    }
}
