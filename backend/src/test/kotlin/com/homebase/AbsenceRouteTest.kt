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
        // Empty lists are omitted by encodeDefaults=false — absent key means empty.
        assertTrue("absences" !in snap)
        assertTrue("settings" !in snap)
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
        // After clearing the only entry, the absences list is empty and omitted (encodeDefaults=false).
        assertTrue("absences" !in state(token))
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
        // After deleting the rule, partTime is empty and omitted (encodeDefaults=false).
        assertTrue("partTime" !in state(token))
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
        // Rejected range creates no closures; empty list is omitted (encodeDefaults=false).
        assertTrue("kitaClosures" !in state(token))
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
        // Rejected batch creates no entries; empty list is omitted (encodeDefaults=false).
        assertTrue("absences" !in state(token))
    }

    @Test
    fun `settings upsert creates with defaults then patches`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val first = client.put("/api/v1/absence/settings/alice/2025") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val body1 = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals("BY", body1["state"]?.jsonPrimitive?.content)
        assertEquals(30.0, body1["allowance"]?.jsonPrimitive?.double)
        assertEquals(15, body1["kindKrankCap"]?.jsonPrimitive?.int)

        val second = client.put("/api/v1/absence/settings/alice/2025") {
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
    fun `settings are stored per year, carryover is per-year and stable fields inherit`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        // Configure 2025 fully.
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/absence/settings/alice/2025") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY","allowance":28,"carryover":5}""")
        }.status)

        // 2026 sets only the carryover; state/allowance must inherit from 2025, carryover must not.
        val y2026 = client.put("/api/v1/absence/settings/alice/2026") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"carryover":2}""")
        }
        assertEquals(HttpStatusCode.OK, y2026.status)
        val b = Json.parseToJsonElement(y2026.bodyAsText()).jsonObject
        assertEquals(2026, b["year"]?.jsonPrimitive?.int)
        assertEquals("BY", b["state"]?.jsonPrimitive?.content)     // inherited
        assertEquals(28.0, b["allowance"]?.jsonPrimitive?.double)  // inherited
        assertEquals(2.0, b["carryover"]?.jsonPrimitive?.double)   // NOT inherited

        // Both years coexist in the snapshot with distinct carryover.
        val rows = state(token)["settings"]!!.jsonArray.map { it.jsonObject }
            .filter { it["userId"]?.jsonPrimitive?.content == "alice" }
            .associate { it["year"]!!.jsonPrimitive.int to it["carryover"]!!.jsonPrimitive.double }
        assertEquals(mapOf(2025 to 5.0, 2026 to 2.0), rows)

        // Editing 2026 again leaves 2025 untouched.
        client.put("/api/v1/absence/settings/alice/2026") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"carryover":9}""")
        }
        val after = state(token)["settings"]!!.jsonArray.map { it.jsonObject }
            .filter { it["userId"]?.jsonPrimitive?.content == "alice" }
            .associate { it["year"]!!.jsonPrimitive.int to it["carryover"]!!.jsonPrimitive.double }
        assertEquals(mapOf(2025 to 5.0, 2026 to 9.0), after)
    }

    @Test
    fun `settings with a non-numeric or out-of-range year returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        for (y in listOf("notayear", "1999", "2201")) {
            val res = client.put("/api/v1/absence/settings/alice/$y") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"state":"BY"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, res.status, "year '$y' should be rejected")
        }
    }

    @Test
    fun `settings with bad state returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/absence/settings/alice/2025") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"state":"XX"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `editing the other user's settings is allowed (shared calendar)`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")

        // The calendar is intentionally shared (#127, reverses #63): alice may edit bob's
        // personal allowance/state, and it is persisted on bob's row.
        val res = client.put("/api/v1/absence/settings/bob/2025") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY","allowance":1}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val settings = state(alice)["settings"]!!.jsonArray
            .associate { it.jsonObject["userId"]!!.jsonPrimitive.content to it.jsonObject }
        val bob = settings["bob"]!!
        assertEquals("BY", bob["state"]?.jsonPrimitive?.content)
        assertEquals(1.0, bob["allowance"]?.jsonPrimitive?.double)
    }

    @Test
    fun `settings for an unknown user returns 404`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")

        // The userExists guard still applies now that the owner-only 403 is gone (#127).
        val res = client.put("/api/v1/absence/settings/ghost/2025") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `each user may edit their own settings`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")

        assertEquals(HttpStatusCode.OK, client.put("/api/v1/absence/settings/alice/2025") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody("""{"state":"BY"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/absence/settings/bob/2025") {
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

    // ---------- Custom holidays (#51) ----------
    // The test DB is built via SchemaUtils (no Flyway), so it starts with no seeded
    // Heiligabend/Silvester rows — the snapshot's customHolidays begins empty here.

    @Test
    fun `custom holiday create appears in the snapshot with half flag`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/absence/holidays") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"month":12,"day":24,"half":true,"label":"Heiligabend"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)

        val holidays = state(token)["customHolidays"]!!.jsonArray
        assertEquals(1, holidays.size)
        val h = holidays[0].jsonObject
        assertEquals(12, h["month"]?.jsonPrimitive?.int)
        assertEquals(24, h["day"]?.jsonPrimitive?.int)
        assertEquals(true, h["half"]?.jsonPrimitive?.boolean)
        assertEquals("Heiligabend", h["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `posting a custom holiday twice on the same date is idempotent`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = """{"month":12,"day":31,"half":true,"label":"Silvester"}"""
        val first = client.post("/api/v1/absence/holidays") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val second = client.post("/api/v1/absence/holidays") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("Silvester", Json.parseToJsonElement(second.bodyAsText()).jsonObject["label"]?.jsonPrimitive?.content)
        assertEquals(1, state(token)["customHolidays"]!!.jsonArray.size)
    }

    @Test
    fun `custom holiday rejects an invalid month or day with 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        for (body in listOf("""{"month":13,"day":1}""", """{"month":0,"day":5}""", """{"month":4,"day":31}""", """{"month":2,"day":30}""")) {
            val res = client.post("/api/v1/absence/holidays") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, res.status, "body $body should be rejected")
        }
        // Feb 29 is allowed — the holiday recurs and is valid in leap years.
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/absence/holidays") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"month":2,"day":29,"label":"Schalttag"}""")
        }.status)
        assertTrue(state(token)["customHolidays"]!!.jsonArray.size == 1)
    }

    @Test
    fun `custom holiday update toggles half and label, delete removes it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val created = client.post("/api/v1/absence/holidays") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"month":12,"day":24,"half":true,"label":"Heiligabend"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/absence/holidays/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"half":false,"label":"Ganztag"}""")
        }
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals(false, body["half"]?.jsonPrimitive?.boolean)
        assertEquals("Ganztag", body["label"]?.jsonPrimitive?.content)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/absence/holidays/$id") { bearerAuth(token) }.status)
        // After deleting the only holiday, customHolidays is empty and omitted (encodeDefaults=false).
        assertTrue("customHolidays" !in state(token))
    }

    @Test
    fun `moving a custom holiday onto an occupied date returns 409`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        suspend fun post(month: Int, day: Int) = client.post("/api/v1/absence/holidays") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"month":$month,"day":$day}""")
        }
        post(12, 24)
        val b = Json.parseToJsonElement(post(12, 31).bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Onto B's own date → fine; onto A's date → 409.
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/absence/holidays/$b") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"month":12,"day":31}""")
        }.status)
        assertEquals(HttpStatusCode.Conflict, client.put("/api/v1/absence/holidays/$b") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"month":12,"day":24}""")
        }.status)
        assertEquals(2, state(token)["customHolidays"]!!.jsonArray.size)
    }
}
