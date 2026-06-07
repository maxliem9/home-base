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
    fun `start on archived project returns 409 and leaves the running timer untouched`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val active = createProject(token, "Aktiv", "#111111")
        val archived = createProject(token, "Archiv", "#222222")
        client.patch("/api/v1/time/projects/$archived/archive") { bearerAuth(token) }

        // a timer is already running on an active project
        val runningId = Json.parseToJsonElement(client.post("/api/v1/time/entries/start") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$active"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val res = client.post("/api/v1/time/entries/start") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$archived"}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("PROJECT_ARCHIVED", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)

        // the rejected start must not have stopped the still-running timer
        val running = client.get("/api/v1/time/running") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, running.status)
        assertEquals(runningId, Json.parseToJsonElement(running.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `manual entry on archived project returns 409`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)
        client.patch("/api/v1/time/projects/$projectId/archive") { bearerAuth(token) }

        val res = client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("PROJECT_ARCHIVED", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `moving an entry onto an archived project returns 409`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val active = createProject(token, "Aktiv", "#111111")
        val archived = createProject(token, "Archiv", "#222222")
        client.patch("/api/v1/time/projects/$archived/archive") { bearerAuth(token) }

        val id = Json.parseToJsonElement(client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$active","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:00:00Z"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val res = client.put("/api/v1/time/entries/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$archived"}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("PROJECT_ARCHIVED", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `editing an existing entry whose project was archived stays allowed`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token)
        val id = Json.parseToJsonElement(client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:00:00Z"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.patch("/api/v1/time/projects/$projectId/archive") { bearerAuth(token) }

        // changing only the description (no project switch) must still work
        val updated = client.put("/api/v1/time/entries/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"description":"Nachgepflegt"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("Nachgepflegt", Json.parseToJsonElement(updated.bodyAsText()).jsonObject["description"]?.jsonPrimitive?.content)

        // and deleting it must still work
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/time/entries/$id") { bearerAuth(token) }.status)
    }

    @Test
    fun `CSV export without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/time/export.csv").status)
    }

    @Test
    fun `CSV export returns header, entry and computed durations`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token, "Garten", "#10B981")
        client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:30:00Z","description":"Rasen"}""")
        }

        val res = client.get("/api/v1/time/export.csv") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.contentType()?.match(ContentType.parse("text/csv")) == true, "expected text/csv, got ${res.contentType()}")
        assertTrue(
            res.headers[HttpHeaders.ContentDisposition]?.contains("zeiterfassung") == true,
            "missing filename in ${res.headers[HttpHeaders.ContentDisposition]}",
        )
        val body = res.bodyAsText()
        assertTrue(body.startsWith("\uFEFF"), "CSV must start with a UTF-8 BOM for Excel")
        assertTrue(body.contains("Projekt;Nutzer;Start;Ende;Dauer (h);Dauer (hh:mm);Beschreibung"), "missing header row")
        assertTrue(body.contains("Garten"), "missing project name")
        assertTrue(body.contains("Rasen"), "missing description")
        // 90 minutes → 1,50 decimal hours and 01:30
        assertTrue(body.contains("1,50"), "missing decimal-hours duration in: $body")
        assertTrue(body.contains("01:30"), "missing hh:mm duration in: $body")
    }

    @Test
    fun `CSV export omits the running timer`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectId = createProject(token, "Laufend", "#222222")
        client.post("/api/v1/time/entries/start") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId"}""")
        }

        val body = client.get("/api/v1/time/export.csv") { bearerAuth(token) }.bodyAsText()
        // only the header row, no data line for the still-running entry
        assertEquals(1, body.trim().lines().size, "running entry leaked into export: $body")
    }

    @Test
    fun `CSV export quotes fields containing the delimiter or quotes`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // Project name carries both the delimiter and a quote; the quote is JSON-escaped
        // in the request body so the stored name is exactly: A;B "C"
        val projectId = Json.parseToJsonElement(client.post("/api/v1/time/projects") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"name":"A;B \"C\"","color":"#333333"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","startedAt":"2026-06-03T08:00:00Z","stoppedAt":"2026-06-03T09:00:00Z","description":"hat; Semikolon"}""")
        }

        val body = client.get("/api/v1/time/export.csv") { bearerAuth(token) }.bodyAsText()
        // project name `A;B "C"` → quoted with doubled inner quotes
        assertTrue(body.contains("\"A;B \"\"C\"\"\""), "project name not RFC-4180 escaped in: $body")
        assertTrue(body.contains("\"hat; Semikolon\""), "description not quoted in: $body")
    }

    @Test
    fun `CSV export can be filtered by project_id`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val projectA = createProject(token, "ProjektEins", "#111111")
        val projectB = createProject(token, "ProjektZwei", "#222222")
        client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectA","startedAt":"2026-06-01T08:00:00Z","stoppedAt":"2026-06-01T09:00:00Z"}""")
        }
        client.post("/api/v1/time/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectB","startedAt":"2026-06-01T10:00:00Z","stoppedAt":"2026-06-01T11:00:00Z"}""")
        }

        val body = client.get("/api/v1/time/export.csv?project_id=$projectA") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("ProjektEins"), "filtered project missing")
        assertTrue(!body.contains("ProjektZwei"), "other project leaked into filtered export")
    }

    @Test
    fun `CSV export rejects an invalid date filter`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/time/export.csv?from=notadate") { bearerAuth(token) }.status,
        )
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
