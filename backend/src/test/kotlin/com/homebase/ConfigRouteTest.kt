package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigRouteTest {

    private suspend fun ApplicationTestBuilder.loginAndGetToken(): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"password123"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.householdName(token: String): String =
        Json.parseToJsonElement(client.get("/api/v1/config") { bearerAuth(token) }.bodyAsText())
            .jsonObject["householdName"]!!.jsonPrimitive.content

    @Test
    fun `GET config without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config").status)
    }

    @Test
    fun `GET config falls back to the configured default when unset`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // configureTestApplication sets no app.householdName, so configureRouting's default applies.
        assertEquals("Mäxchen", householdName(token))
    }

    @Test
    fun `PUT config persists the household name (trimmed) and GET returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.put("/api/v1/config") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"householdName":"  Familie Test  "}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            "Familie Test",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["householdName"]!!.jsonPrimitive.content,
        )
        // persisted for the next read (and thus visible to the other household member)
        assertEquals("Familie Test", householdName(token))

        // a second PUT overwrites rather than inserting a duplicate
        client.put("/api/v1/config") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"householdName":"Zuhause"}""")
        }
        assertEquals("Zuhause", householdName(token))
    }

    @Test
    fun `PUT config rejects a blank name with 400 INVALID_NAME`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"householdName":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_NAME",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT config without token returns 401`() = testApplication {
        configureTestApplication()
        val res = client.put("/api/v1/config") {
            contentType(ContentType.Application.Json); setBody("""{"householdName":"x"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    // --- digest time (#100) ---

    private suspend fun ApplicationTestBuilder.digest(token: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/config/digest") { bearerAuth(token) }.bodyAsText()).jsonObject

    @Test
    fun `GET digest without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config/digest").status)
    }

    @Test
    fun `GET digest defaults to all sections, enabled on, and reports Telegram unconfigured in tests`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = digest(token)
        // the test config sets no digest time override and no Telegram creds
        assertEquals("20:00", body["time"]!!.jsonPrimitive.content)
        // #182: enabled is the in-app toggle (defaults on); telegramConfigured is the env flag.
        assertEquals(true, body["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["telegramConfigured"]!!.jsonPrimitive.boolean)
        // an untouched DB selects every evening section, in display order
        assertEquals(
            listOf(
                "evening_done_today", "evening_new_inbox", "evening_due_tomorrow",
                "evening_absent_tomorrow", "evening_kita_tomorrow",
            ),
            body["sections"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            body["sections"]!!.jsonArray.map { it.jsonPrimitive.content },
            body["availableSections"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `PUT digest persists a normalized time and GET returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.put("/api/v1/config/digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"07:05:30"}""") // seconds are dropped to HH:mm
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("07:05", Json.parseToJsonElement(res.bodyAsText()).jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals("07:05", digest(token)["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT digest toggles enabled and selects sections independently of the time (#182)`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        // Disable + select a subset; the time is left out of the body and must stay at its default.
        val res = client.put("/api/v1/config/digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"enabled":false,"sections":["evening_due_tomorrow","evening_done_today"]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = digest(token)
        assertEquals(false, body["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("20:00", body["time"]!!.jsonPrimitive.content) // untouched
        // stored in canonical display order regardless of request order
        assertEquals(
            listOf("evening_done_today", "evening_due_tomorrow"),
            body["sections"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        // An empty selection is allowed (means "render nothing") and round-trips.
        client.put("/api/v1/config/digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"sections":[]}""")
        }
        assertEquals(0, digest(token)["sections"]!!.jsonArray.size)
    }

    @Test
    fun `PUT digest rejects an unknown section id with 400 INVALID_SECTION (#182)`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            // a morning-only id is not valid for the evening digest
            setBody("""{"sections":["morning_overdue"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_SECTION",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT digest rejects a malformed time with 400 INVALID_TIME`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"25:99"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_TIME",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    // --- morning-briefing time ---

    private suspend fun ApplicationTestBuilder.morningDigest(token: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/config/morning-digest") { bearerAuth(token) }.bodyAsText()).jsonObject

    @Test
    fun `GET morning-digest without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config/morning-digest").status)
    }

    @Test
    fun `GET morning-digest defaults to all sections, enabled on, and reports Telegram unconfigured`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = morningDigest(token)
        // the test config sets no morning-digest override and no Telegram creds
        assertEquals("07:00", body["time"]!!.jsonPrimitive.content)
        assertEquals(true, body["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["telegramConfigured"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("morning_due_today", "morning_overdue", "morning_inbox", "morning_absent", "morning_kita"),
            body["availableSections"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `PUT morning-digest persists a normalized time and GET returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.put("/api/v1/config/morning-digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"06:45:10"}""") // seconds are dropped to HH:mm
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("06:45", Json.parseToJsonElement(res.bodyAsText()).jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals("06:45", morningDigest(token)["time"]!!.jsonPrimitive.content)

        // the morning time is independent of the evening digest time
        assertEquals("20:00", digest(token)["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT morning-digest rejects a malformed time with 400 INVALID_TIME`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/morning-digest") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"24:61"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_TIME",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    // --- recurring-todo safety-net time (#100) ---

    private suspend fun ApplicationTestBuilder.recurring(token: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/config/recurring") { bearerAuth(token) }.bodyAsText()).jsonObject

    @Test
    fun `GET recurring without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config/recurring").status)
    }

    @Test
    fun `GET recurring falls back to the configured default when unset`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // the test config sets no recurring.time override → configureRouting's 00:30 default applies
        assertEquals("00:30", recurring(token)["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT recurring persists a normalized time and GET returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.put("/api/v1/config/recurring") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"06:15:42"}""") // seconds are dropped to HH:mm
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("06:15", Json.parseToJsonElement(res.bodyAsText()).jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals("06:15", recurring(token)["time"]!!.jsonPrimitive.content)

        // a second PUT overwrites rather than inserting a duplicate
        client.put("/api/v1/config/recurring") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"01:00"}""")
        }
        assertEquals("01:00", recurring(token)["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT recurring rejects a malformed time with 400 INVALID_TIME`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/recurring") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"time":"24:00"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_TIME",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    // --- "Erledigt"-history window length (#356) ---

    private suspend fun ApplicationTestBuilder.doneWindowDays(token: String): Int =
        Json.parseToJsonElement(client.get("/api/v1/config/done-window") { bearerAuth(token) }.bodyAsText())
            .jsonObject["days"]!!.jsonPrimitive.int

    @Test
    fun `GET done-window without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config/done-window").status)
    }

    @Test
    fun `GET done-window falls back to the default 14 when unset`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // a fresh DB has no stored value → the code default applies
        assertEquals(14, doneWindowDays(token))
    }

    @Test
    fun `PUT done-window persists the value and GET returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.put("/api/v1/config/done-window") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"days":30}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(30, Json.parseToJsonElement(res.bodyAsText()).jsonObject["days"]!!.jsonPrimitive.int)
        assertEquals(30, doneWindowDays(token))

        // a second PUT overwrites rather than inserting a duplicate
        client.put("/api/v1/config/done-window") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"days":7}""")
        }
        assertEquals(7, doneWindowDays(token))
    }

    @Test
    fun `PUT done-window rejects a value below 1 with 400 INVALID_DAYS`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/done-window") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"days":0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_DAYS",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT done-window rejects a value above the upper bound with 400 INVALID_DAYS`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/done-window") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"days":3651}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_DAYS",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    // --- reminders config (#429 Phase 2a) ----------------------------------

    @Test
    fun `GET reminders config defaults to enabled with no quiet hours`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = Json.parseToJsonElement(
            client.get("/api/v1/config/reminders") { bearerAuth(token) }.bodyAsText(),
        ).jsonObject
        assertEquals(true, body["enabled"]?.jsonPrimitive?.content?.toBoolean())
        // encodeDefaults=false omits the unset quiet-hours bounds
        assertEquals(null, body["quietStart"])
        assertEquals(null, body["quietEnd"])
    }

    @Test
    fun `PUT reminders config persists enabled and a normalized quiet-hours window`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        client.put("/api/v1/config/reminders") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"enabled":false,"quietStart":"22:00","quietEnd":"07:00"}""")
        }
        val body = Json.parseToJsonElement(
            client.get("/api/v1/config/reminders") { bearerAuth(token) }.bodyAsText(),
        ).jsonObject
        assertEquals(false, body["enabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("22:00", body["quietStart"]?.jsonPrimitive?.content)
        assertEquals("07:00", body["quietEnd"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT reminders config rejects a single quiet-hours bound`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/config/reminders") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"enabled":true,"quietStart":"22:00"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_QUIET_HOURS",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT reminders config rejects a quiet window of 12 hours or more`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // 20:00–09:00 is 13h, past the scheduler's 12h catch-up → would silently drop reminders
        val res = client.put("/api/v1/config/reminders") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"enabled":true,"quietStart":"20:00","quietEnd":"09:00"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_QUIET_HOURS",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT reminders config clears quiet hours with empty strings`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        client.put("/api/v1/config/reminders") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"enabled":true,"quietStart":"22:00","quietEnd":"07:00"}""")
        }
        client.put("/api/v1/config/reminders") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"enabled":true,"quietStart":"","quietEnd":""}""")
        }
        val body = Json.parseToJsonElement(
            client.get("/api/v1/config/reminders") { bearerAuth(token) }.bodyAsText(),
        ).jsonObject
        assertEquals(null, body["quietStart"])
        assertEquals(null, body["quietEnd"])
    }
}
