package com.homebase

import com.homebase.db.AbsencesTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.PartTimeRulesTable
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarRouteTest {

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

    private suspend fun ApplicationTestBuilder.createRecipe(token: String, title: String): String {
        val res = client.post("/api/v1/recipes") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"$title","category":"DINNER"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** A date a few days from "today" so it always falls inside the rolling feed window. */
    private fun soon(plusDays: Long = 3): String = LocalDate.now().plusDays(plusDays).toString()

    @Test
    fun `calendar feed without a token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/calendar.ics").status)
    }

    @Test
    fun `calendar feed has the text-calendar content type and a filename`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.get("/api/v1/calendar.ics") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(
            res.contentType().toString().startsWith("text/calendar"),
            "expected text/calendar, got ${res.contentType()}",
        )
        assertTrue(res.headers[HttpHeaders.ContentDisposition]?.contains("homebase.ics") == true)
        val body = res.bodyAsText()
        assertTrue(body.startsWith("BEGIN:VCALENDAR"))
        assertTrue(body.trimEnd().endsWith("END:VCALENDAR"))
        // CRLF line endings (RFC 5545).
        assertTrue(body.contains("\r\n"))
    }

    @Test
    fun `the token may ride in the query param like the image endpoints`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.get("/api/v1/calendar.ics?token=$token")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().startsWith("BEGIN:VCALENDAR"))
    }

    @Test
    fun `a due todo appears as an all-day VEVENT on its due date`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val due = soon()
        client.post("/api/v1/todos") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Zahnarzt","dueDate":"$due"}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("BEGIN:VEVENT"), "expected at least one VEVENT")
        assertTrue(body.contains("SUMMARY:✓ Zahnarzt"), "todo title missing from feed:\n$body")
        // all-day → VALUE=DATE with the compact yyyymmdd form, and a UID stable per source id
        assertTrue(body.contains("DTSTART;VALUE=DATE:${due.replace("-", "")}"))
        assertTrue(body.contains("UID:todo-"))
        assertTrue(body.contains("DTSTAMP:"))
    }

    @Test
    fun `a completed todo is omitted from the feed`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val due = soon()
        val created = client.post("/api/v1/todos") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Erledigt-Aufgabe","dueDate":"$due"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.put("/api/v1/todos/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"status":"DONE"}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertFalse(body.contains("Erledigt-Aufgabe"), "DONE todo should not be in the feed:\n$body")
    }

    @Test
    fun `absences, kita closures and planned meals appear in the feed`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val day = soon(4)

        // Absence (full day) + a half-day for the suffix path.
        transaction {
            AbsencesTable.insert {
                it[id] = UUID.randomUUID(); it[userId] = "alice"; it[date] = LocalDate.parse(day); it[type] = "URLAUB"; it[half] = null
            }
            AbsencesTable.insert {
                it[id] = UUID.randomUUID(); it[userId] = "bob"; it[date] = LocalDate.parse(soon(5)); it[type] = "KIND_KRANK"; it[half] = "vm"
            }
            KitaClosuresTable.insert {
                it[id] = UUID.randomUUID(); it[date] = LocalDate.parse(day); it[label] = "Brückentag"
            }
        }

        // Planned meal (recipe-backed).
        val recipeId = createRecipe(token, "Lasagne")
        client.put("/api/v1/meal-plan/$day/DINNER") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"$recipeId"}""")
        }
        // Planned meal (free text, #293).
        client.put("/api/v1/meal-plan/$day/BREAKFAST") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"dishTitle":"Brötchen holen"}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("UID:absence-"), "absence event missing")
        assertTrue(body.contains("SUMMARY:Urlaub: alice"))
        assertTrue(body.contains("nachmittags") || body.contains("vormittags"), "half-day suffix missing")
        assertTrue(body.contains("UID:kita-"), "kita event missing")
        assertTrue(body.contains("Kita: Brückentag"))
        assertTrue(body.contains("UID:meal-"), "meal event missing")
        assertTrue(body.contains("Abendessen: Lasagne"))
        assertTrue(body.contains("Frühstück: Brötchen holen"))
    }

    @Test
    fun `a part-time free day appears as a weekly-recurring all-day banner`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // A standing "off every <weekday>" rule anchored today, so its first occurrence is in
        // the window. weekday is today's ISO day; BYDAY must match.
        val start = LocalDate.now()
        val isoWeekday = start.dayOfWeek.value // 1..7
        val byDay = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[isoWeekday - 1]
        transaction {
            PartTimeRulesTable.insert {
                it[id] = UUID.randomUUID(); it[userId] = "alice"; it[weekday] = isoWeekday
                it[startDate] = start; it[endDate] = null
            }
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("UID:parttime-"), "part-time event missing:\n$body")
        assertTrue(body.contains("SUMMARY:Teilzeit frei: alice"), "part-time summary missing:\n$body")
        assertTrue(body.contains("RRULE:FREQ=WEEKLY;BYDAY=$byDay"), "weekly RRULE missing:\n$body")
        assertTrue(body.contains("DTSTART;VALUE=DATE:${start.toString().replace("-", "")}"), "all-day DTSTART missing:\n$body")
    }

    @Test
    fun `a timed calendar event appears with a real DTSTART and DTEND`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val day = soon(6)
        client.post("/api/v1/events") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Tierarzt","type":"VET","date":"$day","allDay":false,"startTime":"14:30","endTime":"15:00","location":"Praxis Dr. Müller","notes":"Impfung"}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("UID:event-"), "event UID missing:\n$body")
        assertTrue(body.contains("SUMMARY:🐾 Tierarzt"), "event summary/emoji missing:\n$body")
        assertTrue(body.contains("LOCATION:Praxis Dr. Müller"), "event location missing")
        assertTrue(body.contains("DESCRIPTION:Impfung"), "event notes missing")
        // Timed → UTC stamp form (yyyymmddThhmmssZ), not VALUE=DATE; and OPAQUE (busy).
        assertTrue(body.contains("DTSTART:${day.replace("-", "")}T"), "timed DTSTART missing:\n$body")
        assertTrue(Regex("DTSTART:\\d{8}T\\d{6}Z").containsMatchIn(body), "DTSTART not a UTC instant")
        assertTrue(Regex("DTEND:\\d{8}T\\d{6}Z").containsMatchIn(body), "DTEND not a UTC instant")
        assertTrue(body.contains("TRANSP:OPAQUE"), "timed event should be OPAQUE (busy)")
    }

    @Test
    fun `an all-day calendar event appears as a date banner`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val day = soon(7)
        client.post("/api/v1/events") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Omas Geburtstag","type":"BIRTHDAY","date":"$day","allDay":true}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("SUMMARY:🎂 Omas Geburtstag"), "all-day event summary missing:\n$body")
        assertTrue(body.contains("DTSTART;VALUE=DATE:${day.replace("-", "")}"), "all-day DTSTART missing")
    }

    @Test
    fun `a non-all-day event without a start time falls back to a date banner`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val day = soon(8)
        // allDay=false but no time given — the feed must render a date banner, not a timed VEVENT.
        client.post("/api/v1/events") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Ganztägig ohne Zeit","type":"OTHER","date":"$day","allDay":false}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("SUMMARY:📌 Ganztägig ohne Zeit"), "event summary missing:\n$body")
        assertTrue(body.contains("DTSTART;VALUE=DATE:${day.replace("-", "")}"), "expected an all-day banner:\n$body")
    }

    @Test
    fun `no private-list todo leaks into the feed - not even the owner's own`() = testApplication {
        configureTestApplication()
        val bobToken = loginAndGetToken("bob", "password456")
        // Bob makes a private list and a dated todo in it.
        val listRes = client.post("/api/v1/todos/lists") {
            bearerAuth(bobToken); contentType(ContentType.Application.Json)
            setBody("""{"name":"Bobs Geheim","visibility":"PRIVATE"}""")
        }
        val listId = Json.parseToJsonElement(listRes.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/api/v1/todos") {
            bearerAuth(bobToken); contentType(ContentType.Application.Json)
            setBody("""{"title":"Bobs privates Todo","dueDate":"${soon()}","listId":"$listId"}""")
        }

        // The .ics is exported to an external calendar provider, so a PRIVATE todo must never
        // appear — neither in the partner's feed nor in the owner's own.
        val aliceToken = loginAndGetToken("alice", "password123")
        val aliceFeed = client.get("/api/v1/calendar.ics") { bearerAuth(aliceToken) }.bodyAsText()
        assertFalse(aliceFeed.contains("Bobs privates Todo"), "private todo leaked to the partner:\n$aliceFeed")

        val bobFeed = client.get("/api/v1/calendar.ics") { bearerAuth(bobToken) }.bodyAsText()
        assertFalse(bobFeed.contains("Bobs privates Todo"), "private todo leaked into the owner's own exported feed:\n$bobFeed")
    }

    @Test
    fun `a todo in a shared list is exported`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listRes = client.post("/api/v1/todos/lists") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"name":"Gemeinsam","visibility":"SHARED"}""")
        }
        val listId = Json.parseToJsonElement(listRes.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/api/v1/todos") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Geteiltes Todo","dueDate":"${soon()}","listId":"$listId"}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        assertTrue(body.contains("Geteiltes Todo"), "a SHARED-list todo should be exported:\n$body")
    }

    @Test
    fun `text escaping and a long summary fold correctly`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        // A title with a comma + semicolon (must be escaped) and long enough to force a fold.
        val title = "Einkauf: Milch, Brot; Käse und ganz viele weitere Dinge fuer das lange Wochenende"
        client.post("/api/v1/todos") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"$title","dueDate":"${soon()}"}""")
        }

        val body = client.get("/api/v1/calendar.ics") { bearerAuth(token) }.bodyAsText()
        // commas/semicolons inside the value are backslash-escaped
        assertTrue(body.contains("Milch\\, Brot\\; K"), "TEXT not escaped:\n$body")
        // folded continuation lines begin with CRLF + a single space; no raw content line exceeds 75 octets
        val tooLong = body.split("\r\n").firstOrNull { it.toByteArray(Charsets.UTF_8).size > 75 }
        assertTrue(tooLong == null, "found an unfolded line >75 octets: $tooLong")
    }
}
