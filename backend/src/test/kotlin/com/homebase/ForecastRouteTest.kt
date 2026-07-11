package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wochensoll targets + forecast (#31). The forecast tests pin the day via ?date=
 * (week Mon 2026-06-08 … Sun 2026-06-14 — no statutory Berlin holiday in it), so the
 * numbers stay deterministic no matter when the suite runs.
 */
class ForecastRouteTest {

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

    private suspend fun ApplicationTestBuilder.createProject(token: String, name: String = "Arbeit"): String {
        val res = client.post("/api/v1/time/projects") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","color":"#4F46E5"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.putTarget(token: String, user: String, projectId: String, body: String) =
        client.put("/api/v1/time/targets/$user/$projectId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.createEntry(token: String, projectId: String, startedAt: String, stoppedAt: String) {
        val res = client.post("/api/v1/time/entries") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","startedAt":"$startedAt","stoppedAt":"$stoppedAt"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
    }

    private suspend fun ApplicationTestBuilder.forecastUser(token: String, date: String?, user: String): JsonObject {
        val res = client.get("/api/v1/time/forecast${date?.let { "?date=$it" } ?: ""}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["users"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["userId"]?.jsonPrimitive?.content == user }
    }

    // ---------- targets ----------

    @Test
    fun `targets upsert, list and default handover`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token, "Arbeit")
        val p2 = createProject(token, "Nebenjob")

        // fresh DB → no targets
        val empty = client.get("/api/v1/time/targets") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, empty.status)
        assertEquals(0, Json.parseToJsonElement(empty.bodyAsText()).jsonArray.size)

        // create with hours + default
        val created = putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")
        assertEquals(HttpStatusCode.OK, created.status)
        val dto = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals(40.0, dto["weeklyHours"]?.jsonPrimitive?.double)
        assertEquals(true, dto["isDefault"]?.jsonPrimitive?.boolean)

        // second project, partial update (hours only)
        putTarget(token, "alice", p2, """{"weeklyHours":2.5}""")
        // making p2 the default clears p1's flag
        putTarget(token, "alice", p2, """{"isDefault":true}""")

        val list = Json.parseToJsonElement(
            client.get("/api/v1/time/targets") { bearerAuth(token) }.bodyAsText()
        ).jsonArray.map { it.jsonObject }
        assertEquals(2, list.size)
        val byProject = list.associateBy { it["projectId"]?.jsonPrimitive?.content }
        assertEquals(false, byProject[p1]?.get("isDefault")?.jsonPrimitive?.boolean)
        assertEquals(true, byProject[p2]?.get("isDefault")?.jsonPrimitive?.boolean)
        assertEquals(2.5, byProject[p2]?.get("weeklyHours")?.jsonPrimitive?.double)
    }

    @Test
    fun `target validation rejects bad input`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)

        assertEquals(HttpStatusCode.BadRequest, putTarget(token, "alice", p1, """{"weeklyHours":-1}""").status)
        assertEquals(HttpStatusCode.BadRequest, putTarget(token, "alice", p1, """{"weeklyHours":200}""").status)
        assertEquals(HttpStatusCode.BadRequest, putTarget(token, "alice", "not-a-uuid", """{"weeklyHours":10}""").status)
        assertEquals(HttpStatusCode.NotFound, putTarget(token, "nobody", p1, """{"weeklyHours":10}""").status)
        assertEquals(
            HttpStatusCode.NotFound,
            putTarget(token, "alice", "00000000-0000-0000-0000-000000000099", """{"weeklyHours":10}""").status,
        )
    }

    @Test
    fun `first configured hours auto-assign the default project`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token, "Arbeit")
        val p2 = createProject(token, "Nebenjob")

        // no default exists yet → the first row with hours becomes it
        val first = putTarget(token, "alice", p1, """{"weeklyHours":40}""")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(true, Json.parseToJsonElement(first.bodyAsText()).jsonObject["isDefault"]?.jsonPrimitive?.boolean)

        // a later row with hours does not steal the existing default
        val second = putTarget(token, "alice", p2, """{"weeklyHours":5}""")
        assertEquals(false, Json.parseToJsonElement(second.bodyAsText()).jsonObject["isDefault"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `removing the default while hours remain is rejected`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":40}""") // auto-default

        val rejected = putTarget(token, "alice", p1, """{"isDefault":false}""")
        assertEquals(HttpStatusCode.Conflict, rejected.status)
        assertEquals(
            "DEFAULT_REQUIRED",
            Json.parseToJsonElement(rejected.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )

        // once no hours are configured anymore, the default may go
        putTarget(token, "alice", p1, """{"weeklyHours":0}""")
        assertEquals(HttpStatusCode.OK, putTarget(token, "alice", p1, """{"isDefault":false}""").status)
    }

    @Test
    fun `partner may configure the other person's target`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken() // alice
        val p1 = createProject(token)

        val res = putTarget(token, "bob", p1, """{"weeklyHours":20,"isDefault":true}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("bob", Json.parseToJsonElement(res.bodyAsText()).jsonObject["userId"]?.jsonPrimitive?.content)
    }

    // ---------- forecast ----------

    @Test
    fun `forecast redistributes the week remainder over the remaining days`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")

        // Mon 8h, Tue 6h, Wed (the forecast day) 2h so far
        createEntry(token, p1, "2026-06-08T09:00:00Z", "2026-06-08T17:00:00Z")
        createEntry(token, p1, "2026-06-09T09:00:00Z", "2026-06-09T15:00:00Z")
        createEntry(token, p1, "2026-06-10T09:00:00Z", "2026-06-10T11:00:00Z")

        val u = forecastUser(token, "2026-06-10", "alice")
        assertEquals(5.0, u["workdayCount"]?.jsonPrimitive?.double)
        assertEquals(144000, u["weekTargetSeconds"]?.jsonPrimitive?.long) // 40h
        assertEquals(57600, u["weekRecordedSeconds"]?.jsonPrimitive?.long) // 16h
        assertEquals(0, u["weekCreditedSeconds"]?.jsonPrimitive?.long)
        assertEquals(86400, u["weekRemainingSeconds"]?.jsonPrimitive?.long) // 24h
        // open after Mon+Tue: 40 − 14 = 26h over Wed–Fri → 8:40h today
        assertEquals(31200, u["todayTargetSeconds"]?.jsonPrimitive?.long)
        assertEquals(7200, u["todayRecordedSeconds"]?.jsonPrimitive?.long)
        assertEquals(24000, u["todayRemainingSeconds"]?.jsonPrimitive?.long)

        val projects = u["projects"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, projects.size)
        assertEquals(57600, projects[0]["recordedSeconds"]?.jsonPrimitive?.long)
        assertEquals(-86400, projects[0]["deltaSeconds"]?.jsonPrimitive?.long)
    }

    @Test
    fun `part-time, absence and half custom holiday credit the default project`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":36,"isDefault":true}""")

        // Mondays are part-time-free → 4 workdays, 9h daily target
        client.post("/api/v1/absence/parttime") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","weekday":1,"start":"2026-01-01"}""")
        }
        // sick on Thu (full credit), half custom holiday on Fri (0.5 credit)
        client.post("/api/v1/absence/entries") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-06-11","type":"KRANK"}""")
        }
        client.post("/api/v1/absence/holidays") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"month":6,"day":12,"half":true,"label":"Brückentag"}""")
        }
        // Tue 4h recorded
        createEntry(token, p1, "2026-06-09T09:00:00Z", "2026-06-09T13:00:00Z")

        val u = forecastUser(token, "2026-06-10", "alice")
        assertEquals(4.0, u["workdayCount"]?.jsonPrimitive?.double)
        assertEquals(129600, u["weekTargetSeconds"]?.jsonPrimitive?.long) // 36h
        // Thu 9h + Fri 4.5h credited
        assertEquals(48600, u["weekCreditedSeconds"]?.jsonPrimitive?.long)
        // open: 36 − 13.5 − 4 = 18.5h over portions Wed(1) + Fri(0.5) → 12:20h today
        assertEquals(44400, u["todayTargetSeconds"]?.jsonPrimitive?.long)

        val proj = u["projects"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals(48600, proj["creditedSeconds"]?.jsonPrimitive?.long)
        assertEquals(14400, proj["recordedSeconds"]?.jsonPrimitive?.long)
        // 4h + 13.5h − 36h = −18.5h
        assertEquals(-66600, proj["deltaSeconds"]?.jsonPrimitive?.long)
    }

    @Test
    fun `statutory holiday counts as a credited workday`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")

        // week of Pfingstmontag 2026-05-25 (nationwide; alice defaults to state BE)
        val u = forecastUser(token, "2026-05-26", "alice")
        assertEquals(5.0, u["workdayCount"]?.jsonPrimitive?.double) // holiday still divides
        assertEquals(28800, u["weekCreditedSeconds"]?.jsonPrimitive?.long) // Mon 8h credited
        // 40 − 8 = 32h over Tue–Fri → 8h today
        assertEquals(28800, u["todayTargetSeconds"]?.jsonPrimitive?.long)
    }

    @Test
    fun `expected end is present while a timer runs and absent otherwise`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")

        // no timer → no expected end (today, whatever weekday the suite runs on)
        assertNull(forecastUser(token, null, "alice")["expectedEndAt"])

        client.post("/api/v1/time/entries/start") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$p1"}""")
        }
        val end = forecastUser(token, null, "alice")["expectedEndAt"]?.jsonPrimitive?.content
        assertNotNull(end)
        // parses as an instant and never lies in the past
        assertTrue(Instant.parse(end) >= Instant.now().minusSeconds(60))

        // a pinned past date never carries an expected end, even while a timer runs
        assertNull(forecastUser(token, "2026-01-07", "alice")["expectedEndAt"])
    }

    @Test
    fun `users without targets get a zero forecast`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        createProject(token)

        val u = forecastUser(token, "2026-06-10", "bob")
        assertEquals(0.0, u["weeklyTargetHours"]?.jsonPrimitive?.double)
        assertEquals(0, u["weekTargetSeconds"]?.jsonPrimitive?.long)
        assertEquals(0, u["todayTargetSeconds"]?.jsonPrimitive?.long)
        assertNull(u["expectedEndAt"])
    }

    // ---------- credits (historical, #31) ----------

    private suspend fun ApplicationTestBuilder.getCredits(token: String, from: String, to: String): List<JsonObject> {
        val res = client.get("/api/v1/time/credits?from=$from&to=$to") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonArray.map { it.jsonObject }
    }

    @Test
    fun `credits book absences to the default project at the daily target`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""") // 5 workdays → 8h/day

        // full sick day Wed, half child-sick Thu — week Mon 2026-06-08 … Sun 2026-06-14
        client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-06-10","type":"KRANK"}""")
        }
        client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-06-11","type":"KIND_KRANK","half":"vm"}""")
        }

        val credits = getCredits(token, "2026-06-08", "2026-06-14").associateBy { it["date"]?.jsonPrimitive?.content }
        assertEquals(2, credits.size)
        val wed = credits["2026-06-10"]!!
        assertEquals(28800, wed["seconds"]?.jsonPrimitive?.long) // full 8h
        assertEquals("KRANK", wed["type"]?.jsonPrimitive?.content)
        assertEquals(p1, wed["projectId"]?.jsonPrimitive?.content)
        assertEquals("alice", wed["userId"]?.jsonPrimitive?.content)
        val thu = credits["2026-06-11"]!!
        assertEquals(14400, thu["seconds"]?.jsonPrimitive?.long) // half → 4h
        assertEquals("KIND_KRANK", thu["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `credits include statutory holidays and skip days outside the range`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")

        // Pfingstmontag 2026-05-25 (nationwide; alice defaults to BE). Query only Mon–Tue,
        // so the holiday is in range but the rest of the week is not.
        val credits = getCredits(token, "2026-05-25", "2026-05-26")
        assertEquals(1, credits.size)
        assertEquals("2026-05-25", credits[0]["date"]?.jsonPrimitive?.content)
        assertEquals(28800, credits[0]["seconds"]?.jsonPrimitive?.long) // 8h
        assertEquals("FEIERTAG", credits[0]["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `credits are empty without a default project and require both bounds`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token)
        // hours but the auto-default still lands — so drop hours to 0 to have no credit target
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")
        client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"bob","date":"2026-06-10","type":"KRANK"}""")
        }
        // bob has no target/default → his sick day yields no credit
        assertTrue(getCredits(token, "2026-06-08", "2026-06-14").none { it["userId"]?.jsonPrimitive?.content == "bob" })

        // both bounds are required
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/time/credits?from=2026-06-08") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/time/credits?to=2026-06-14") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/time/credits?from=nope&to=2026-06-14") { bearerAuth(token) }.status)
    }

    @Test
    fun `CSV export includes absence credit rows`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val p1 = createProject(token, "Arbeit")
        putTarget(token, "alice", p1, """{"weeklyHours":40,"isDefault":true}""")
        createEntry(token, p1, "2026-06-09T09:00:00Z", "2026-06-09T13:00:00Z") // Tue 4h recorded
        client.post("/api/v1/absence/entries") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","date":"2026-06-10","type":"KRANK"}""")
        }

        val body = client.get("/api/v1/time/export.csv?from=2026-06-08T00:00:00Z&to=2026-06-14T23:59:59Z") {
            bearerAuth(token)
        }.bodyAsText()
        assertTrue(body.contains("Krank (Zeitgutschrift)"), "sick-day credit row missing in: $body")
        assertTrue(body.contains("8,00"), "credited decimal hours missing in: $body")
        assertTrue(body.contains("08:00"), "credited hh:mm missing in: $body")
        // the recorded Tue entry is still there
        assertTrue(body.contains("Arbeit"), "recorded entry missing")
    }
}
