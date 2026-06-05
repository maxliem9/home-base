package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbsenceRouteTest {

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

    private suspend fun ApplicationTestBuilder.state(token: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/absence") { bearerAuth(token) }.bodyAsText()).jsonObject

    @Test
    fun `GET absence without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/absence").status)
    }

    @Test
    fun `GET absence returns an empty snapshot listing both seeded users`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val snap = state(token)
        assertEquals(listOf("alice", "bob"), snap["users"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(snap["absences"]!!.jsonArray.isEmpty())
        assertTrue(snap["settings"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `POST entry sets an absence and re-posting upserts it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-04-06","type":"URLAUB","half":"vm"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)

        // upsert: same day again with a different type → still one entry
        client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-04-06","type":"KRANK"}""")
        }
        val absences = state(token)["absences"]!!.jsonArray
        assertEquals(1, absences.size)
        assertEquals("KRANK", absences[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(absences[0].jsonObject["half"].let { it == null || it is JsonNull })
    }

    @Test
    fun `POST entry with bad type returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-04-06","type":"FERIEN"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `POST entry for unknown user returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"ghost","date":"2026-04-06","type":"URLAUB"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `DELETE entry clears it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-04-06","type":"URLAUB"}""")
        }
        val del = client.delete("/api/v1/absence/entries?userId=alice&date=2026-04-06") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertTrue(state(token)["absences"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `batch applies a type to many dates and clears with null type`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        client.post("/api/v1/absence/entries/batch") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","type":"URLAUB","dates":["2026-07-27","2026-07-28","2026-07-29"]}""")
        }
        assertEquals(3, state(token)["absences"]!!.jsonArray.size)

        // clear: type null over a subset
        client.post("/api/v1/absence/entries/batch") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","type":null,"dates":["2026-07-27","2026-07-28"]}""")
        }
        assertEquals(1, state(token)["absences"]!!.jsonArray.size)
    }

    @Test
    fun `part-time rule create, update and delete`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/absence/parttime") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","weekday":1,"start":"2026-01-01","end":"2026-04-30"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/absence/parttime/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"weekday":5,"start":"2026-03-01","end":null}""")
        }
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals(5, body["weekday"]?.jsonPrimitive?.int)
        assertTrue(body["end"].let { it == null || it is JsonNull })

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/absence/parttime/$id") { bearerAuth(token) }.status)
        assertTrue(state(token)["partTime"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `kita range skips weekends`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // 2026-07-27 (Mon) .. 2026-08-02 (Sun) → 5 weekdays
        client.post("/api/v1/absence/kita/range") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"from":"2026-07-27","to":"2026-08-02","label":"Sommerschließung"}""")
        }
        assertEquals(5, state(token)["kitaClosures"]!!.jsonArray.size)
    }

    @Test
    fun `posting a kita closure twice on the same date is idempotent`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = """{"date":"2026-12-24","label":"Heiligabend"}"""
        val first = client.post("/api/v1/absence/kita") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, first.status)
        // Same date again → no duplicate row, returns the existing closure with 200.
        val second = client.post("/api/v1/absence/kita") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, second.status)
        // The second POST returns the existing closure, unchanged.
        assertEquals("Heiligabend", Json.parseToJsonElement(second.bodyAsText()).jsonObject["label"]?.jsonPrimitive?.content)
        val closures = state(token)["kitaClosures"]!!.jsonArray
        assertEquals(1, closures.size)
    }

    @Test
    fun `moving a kita closure onto an occupied date returns 409`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        suspend fun postKita(date: String) = client.post("/api/v1/absence/kita") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"date":"$date"}""")
        }
        postKita("2026-12-24")
        val b = Json.parseToJsonElement(postKita("2026-12-25").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Move B onto B's own date → fine (no-op against itself); onto A's date → 409.
        val ok = client.put("/api/v1/absence/kita/$b") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-12-25"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val conflict = client.put("/api/v1/absence/kita/$b") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-12-24"}""")
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        // Both closures still intact on their original dates.
        assertEquals(2, state(token)["kitaClosures"]!!.jsonArray.size)
    }

    @Test
    fun `kita range re-run is idempotent and does not duplicate closures`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = """{"from":"2026-07-27","to":"2026-08-02","label":"Sommerschließung"}"""
        repeat(2) {
            client.post("/api/v1/absence/kita/range") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        assertEquals(5, state(token)["kitaClosures"]!!.jsonArray.size)
    }

    @Test
    fun `kita range rejects an oversized span with 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.post("/api/v1/absence/kita/range") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"from":"2026-01-01","to":"2029-01-01"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(state(token)["kitaClosures"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `kita range accepts the maximum span and rejects one day past it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val from = LocalDate.of(2026, 1, 1)
        // inclusive span of exactly the cap (731 days) is allowed…
        val ok = client.post("/api/v1/absence/kita/range") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"from":"$from","to":"${from.plusDays(730)}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        // …one day more is rejected.
        val tooLong = client.post("/api/v1/absence/kita/range") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"from":"$from","to":"${from.plusDays(731)}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }

    @Test
    fun `batch accepts exactly the maximum number of dates`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val dates = (0 until 366).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }
        val datesJson = dates.joinToString(",", "[", "]") { "\"$it\"" }
        val res = client.post("/api/v1/absence/entries/batch") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","type":"URLAUB","dates":$datesJson}""")
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
        assertEquals(366, state(token)["absences"]!!.jsonArray.size)
    }

    @Test
    fun `batch rejects too many dates with 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val dates = (1..400).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }
        val datesJson = dates.joinToString(",", "[", "]") { "\"$it\"" }
        val res = client.post("/api/v1/absence/entries/batch") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","type":"URLAUB","dates":$datesJson}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(state(token)["absences"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `settings upsert creates with defaults then patches`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val first = client.put("/api/v1/absence/settings/alice") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val body1 = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals("BY", body1["state"]?.jsonPrimitive?.content)
        assertEquals(30.0, body1["allowance"]?.jsonPrimitive?.double)
        assertEquals(15, body1["kindKrankCap"]?.jsonPrimitive?.int)

        val second = client.put("/api/v1/absence/settings/alice") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"allowance":24,"carryover":2.5,"carryoverExpires":"2026-03-31"}""")
        }
        val body2 = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals("BY", body2["state"]?.jsonPrimitive?.content) // preserved
        assertEquals(24.0, body2["allowance"]?.jsonPrimitive?.double)
        assertEquals(2.5, body2["carryover"]?.jsonPrimitive?.double)
        assertEquals("2026-03-31", body2["carryoverExpires"]?.jsonPrimitive?.content)
    }

    @Test
    fun `settings with bad state returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/absence/settings/alice") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"state":"XX"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `editing someone else's settings is forbidden`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")

        // alice tries to overwrite bob's personal allowance/state → 403, and nothing is persisted.
        val res = client.put("/api/v1/absence/settings/bob") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY","allowance":1}""")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertTrue(state(alice)["settings"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `each user may edit their own settings`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")

        assertEquals(HttpStatusCode.OK, client.put("/api/v1/absence/settings/alice") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/absence/settings/bob") {
            bearerAuth(bob); contentType(ContentType.Application.Json)
            setBody("""{"state":"HH"}""")
        }.status)

        val settings = state(alice)["settings"]!!.jsonArray
            .associate { it.jsonObject["userId"]!!.jsonPrimitive.content to it.jsonObject["state"]!!.jsonPrimitive.content }
        assertEquals(mapOf("alice" to "BY", "bob" to "HH"), settings)
    }

    @Test
    fun `the shared calendar lets one user edit another's days`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")

        // The household planner is intentionally shared: alice may set bob's absence.
        val res = client.post("/api/v1/absence/entries") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"userId":"bob","date":"2026-04-06","type":"URLAUB"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val absences = state(alice)["absences"]!!.jsonArray
        assertEquals(1, absences.size)
        assertEquals("bob", absences[0].jsonObject["userId"]?.jsonPrimitive?.content)
    }
}
