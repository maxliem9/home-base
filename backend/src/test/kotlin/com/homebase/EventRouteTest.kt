package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventRouteTest {

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

    private suspend fun ApplicationTestBuilder.create(token: String, body: String): HttpResponse =
        client.post("/api/v1/events") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }

    private suspend fun ApplicationTestBuilder.range(token: String, from: String, to: String): JsonArray =
        Json.parseToJsonElement(
            client.get("/api/v1/events?from=$from&to=$to") { bearerAuth(token) }.bodyAsText()
        ).jsonArray

    @Test
    fun `GET events without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/events?from=2026-06-01&to=2026-06-30").status)
    }

    @Test
    fun `GET without from or to returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/events") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/events?from=2026-06-01") { bearerAuth(token) }.status)
    }

    @Test
    fun `GET with from after to returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/events?from=2026-06-30&to=2026-06-01") { bearerAuth(token) }.status)
    }

    @Test
    fun `GET with an oversized range returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.get("/api/v1/events?from=2026-01-01&to=2027-12-31") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `POST creates an all-day event and GET returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = create(token, """{"title":"Omas Geburtstag","type":"BIRTHDAY","date":"2026-06-15","allDay":true}""")
        assertEquals(HttpStatusCode.Created, res.status)
        val created = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("Omas Geburtstag", created["title"]?.jsonPrimitive?.content)
        assertEquals("BIRTHDAY", created["type"]?.jsonPrimitive?.content)
        assertEquals(true, created["allDay"]?.jsonPrimitive?.boolean)
        assertEquals("alice", created["createdBy"]?.jsonPrimitive?.content)
        // all-day → no time; encodeDefaults=false drops the null fields
        assertTrue(created["startTime"].let { it == null || it is JsonNull })

        val events = range(token, "2026-06-01", "2026-06-30")
        assertEquals(1, events.size)
        assertEquals("Omas Geburtstag", events[0].jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST creates a timed event with start and end time`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = create(token, """{"title":"Arzt","type":"APPOINTMENT","date":"2026-06-15","allDay":false,"startTime":"09:30","endTime":"10:15","location":"Praxis Dr. Müller","notes":"Nüchtern kommen"}""")
        assertEquals(HttpStatusCode.Created, res.status)
        val e = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(false, e["allDay"]?.jsonPrimitive?.boolean)
        assertEquals("09:30", e["startTime"]?.jsonPrimitive?.content)
        assertEquals("10:15", e["endTime"]?.jsonPrimitive?.content)
        assertEquals("Praxis Dr. Müller", e["location"]?.jsonPrimitive?.content)
        assertEquals("Nüchtern kommen", e["notes"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST defaults type to OTHER when omitted`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = create(token, """{"title":"Irgendwas","date":"2026-06-15"}""")
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("OTHER", Json.parseToJsonElement(res.bodyAsText()).jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST with a blank title returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, create(token, """{"title":"   ","date":"2026-06-15"}""").status)
    }

    @Test
    fun `POST with an invalid date returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, create(token, """{"title":"X","date":"15-06-2026"}""").status)
    }

    @Test
    fun `POST with an unknown type returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, create(token, """{"title":"X","type":"WEDDING","date":"2026-06-15"}""").status)
    }

    @Test
    fun `POST all-day with a time returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, create(token, """{"title":"X","date":"2026-06-15","allDay":true,"startTime":"09:00"}""").status)
    }

    @Test
    fun `POST timed with end before start returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, create(token, """{"title":"X","date":"2026-06-15","allDay":false,"startTime":"10:00","endTime":"09:00"}""").status)
    }

    @Test
    fun `POST timed with end but no start returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        assertEquals(HttpStatusCode.BadRequest, create(token, """{"title":"X","date":"2026-06-15","allDay":false,"endTime":"09:00"}""").status)
    }

    @Test
    fun `PUT replaces an event`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            create(token, """{"title":"Arzt","type":"APPOINTMENT","date":"2026-06-15","allDay":false,"startTime":"09:30"}""").bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        val put = client.put("/api/v1/events/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Tierarzt","type":"VET","date":"2026-06-16","allDay":true}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val e = Json.parseToJsonElement(put.bodyAsText()).jsonObject
        assertEquals("Tierarzt", e["title"]?.jsonPrimitive?.content)
        assertEquals("VET", e["type"]?.jsonPrimitive?.content)
        assertEquals("2026-06-16", e["date"]?.jsonPrimitive?.content)
        assertEquals(true, e["allDay"]?.jsonPrimitive?.boolean)
        // switching to all-day clears the previous time
        assertTrue(e["startTime"].let { it == null || it is JsonNull })
    }

    @Test
    fun `PUT on an unknown id returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/events/00000000-0000-0000-0000-0000000000ff") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"X","date":"2026-06-15"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `PUT with an invalid uuid returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val res = client.put("/api/v1/events/not-a-uuid") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"X","date":"2026-06-15"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `DELETE removes an event and is 404 the second time`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = Json.parseToJsonElement(
            create(token, """{"title":"X","date":"2026-06-15"}""").bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/events/$id") { bearerAuth(token) }.status)
        assertTrue(range(token, "2026-06-01", "2026-06-30").isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/events/$id") { bearerAuth(token) }.status)
    }

    @Test
    fun `events are household-shared - bob sees alice's event and may edit it`() = testApplication {
        configureTestApplication()
        val aliceToken = loginAndGetToken("alice", "password123")
        val bobToken = loginAndGetToken("bob", "password456")

        val id = Json.parseToJsonElement(
            create(aliceToken, """{"title":"Arzt","date":"2026-06-15"}""").bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        // bob sees it
        val bobView = range(bobToken, "2026-06-01", "2026-06-30")
        assertEquals(1, bobView.size)
        // and may edit it (no owner check)
        val put = client.put("/api/v1/events/$id") {
            bearerAuth(bobToken); contentType(ContentType.Application.Json)
            setBody("""{"title":"Arzt verschoben","date":"2026-06-20"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)
        // created_by stays the original creator
        assertEquals("alice", Json.parseToJsonElement(put.bodyAsText()).jsonObject["createdBy"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET range only returns events within the range`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        create(token, """{"title":"inside","date":"2026-06-15"}""")
        create(token, """{"title":"outside","date":"2026-07-15"}""")

        val events = range(token, "2026-06-01", "2026-06-30")
        assertEquals(1, events.size)
        assertEquals("inside", events[0].jsonObject["title"]?.jsonPrimitive?.content)
    }
}
