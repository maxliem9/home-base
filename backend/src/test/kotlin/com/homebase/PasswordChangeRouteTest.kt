package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordChangeRouteTest {

    private suspend fun ApplicationTestBuilder.login(username: String, password: String): HttpResponse =
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }

    private suspend fun ApplicationTestBuilder.tokenFor(username: String, password: String): String =
        Json.parseToJsonElement(login(username, password).bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.changePassword(token: String, current: String, next: String): HttpResponse =
        client.put("/api/v1/users/me/password") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"$current","newPassword":"$next"}""")
        }

    @Test
    fun `change password without token returns 401`() = testApplication {
        configureTestApplication()
        val res = client.put("/api/v1/users/me/password") {
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"x","newPassword":"yyyyyyyy"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `valid change updates the password — new logs in, old no longer does`() = testApplication {
        configureTestApplication()
        val token = tokenFor("alice", "password123")

        assertEquals(HttpStatusCode.NoContent, changePassword(token, "password123", "brandneu-99").status)

        // the new password authenticates…
        assertEquals(HttpStatusCode.OK, login("alice", "brandneu-99").status)
        // …and the old one no longer does
        assertEquals(HttpStatusCode.Unauthorized, login("alice", "password123").status)
    }

    @Test
    fun `wrong current password returns 400 INVALID_PASSWORD and leaves it unchanged`() = testApplication {
        configureTestApplication()
        val token = tokenFor("alice", "password123")

        val res = changePassword(token, "wrong-current", "brandneu-99")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_PASSWORD",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
        // the original password still works — nothing was changed
        assertEquals(HttpStatusCode.OK, login("alice", "password123").status)
    }

    @Test
    fun `too-short new password returns 400 WEAK_PASSWORD`() = testApplication {
        configureTestApplication()
        val token = tokenFor("alice", "password123")

        val res = changePassword(token, "password123", "short")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "WEAK_PASSWORD",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
    }
}
