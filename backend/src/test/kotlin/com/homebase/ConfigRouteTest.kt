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
    fun `GET digest falls back to the configured default and reports Telegram disabled in tests`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val body = digest(token)
        // the test config sets no digest time override and no Telegram creds
        assertEquals("20:00", body["time"]!!.jsonPrimitive.content)
        assertEquals(false, body["enabled"]!!.jsonPrimitive.boolean)
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
}
