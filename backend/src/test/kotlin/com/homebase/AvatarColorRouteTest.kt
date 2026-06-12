package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-user avatar colour (Teil von #100). GET /users exposes the household-visible
 * avatarHue (so the partner sees it); PUT /users/me/avatar-color sets/clears the
 * caller's own hue. avatarHue rides the shared roster on purpose (own-read-only
 * user_prefs would hide it from the partner); the setter is own-only via /users/me.
 */
class AvatarColorRouteTest {

    private suspend fun ApplicationTestBuilder.tokenFor(username: String, password: String): String =
        Json.parseToJsonElement(
            client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$username","password":"$password"}""")
            }.bodyAsText(),
        ).jsonObject["token"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.setColor(token: String, body: String): HttpResponse =
        client.put("/api/v1/users/me/avatar-color") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.usersJson(token: String): JsonArray =
        Json.parseToJsonElement(
            client.get("/api/v1/users") { bearerAuth(token) }.bodyAsText(),
        ).jsonArray

    private fun JsonArray.hueOf(username: String): JsonElement? =
        map { it.jsonObject }
            .first { it["username"]?.jsonPrimitive?.content == username }["avatarHue"]

    @Test
    fun `GET users omits avatarHue until one is set, then includes it`() = testApplication {
        configureTestApplication()
        val token = tokenFor("alice", "password123")

        // encodeDefaults=false (#46): null hue is omitted entirely.
        assertNull(usersJson(token).hueOf("alice"))

        assertEquals(HttpStatusCode.NoContent, setColor(token, """{"hue":200}""").status)

        assertEquals(200, usersJson(token).hueOf("alice")?.jsonPrimitive?.int)
    }

    @Test
    fun `partner sees the chosen colour via the shared roster`() = testApplication {
        configureTestApplication()
        val alice = tokenFor("alice", "password123")
        val bob = tokenFor("bob", "password456")

        assertEquals(HttpStatusCode.NoContent, setColor(alice, """{"hue":42}""").status)

        // bob reads alice's colour from GET /users — it is household-visible by design.
        assertEquals(42, usersJson(bob).hueOf("alice")?.jsonPrimitive?.int)
    }

    @Test
    fun `PUT null clears the hue back to automatic`() = testApplication {
        configureTestApplication()
        val token = tokenFor("alice", "password123")

        setColor(token, """{"hue":123}""")
        assertEquals(123, usersJson(token).hueOf("alice")?.jsonPrimitive?.int)

        // null (and an empty body, hue defaults to null) clears it.
        assertEquals(HttpStatusCode.NoContent, setColor(token, """{"hue":null}""").status)
        assertNull(usersJson(token).hueOf("alice"))
    }

    @Test
    fun `out-of-range hue returns 400 INVALID_HUE`() = testApplication {
        configureTestApplication()
        val token = tokenFor("alice", "password123")

        for (bad in listOf("-1", "360", "999")) {
            val res = setColor(token, """{"hue":$bad}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, "hue=$bad should be rejected")
            assertEquals(
                "INVALID_HUE",
                Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
            )
        }
        // boundary values are accepted
        assertEquals(HttpStatusCode.NoContent, setColor(token, """{"hue":0}""").status)
        assertEquals(HttpStatusCode.NoContent, setColor(token, """{"hue":359}""").status)
    }

    @Test
    fun `setting avatar colour requires authentication`() = testApplication {
        configureTestApplication()
        val res = client.put("/api/v1/users/me/avatar-color") {
            contentType(ContentType.Application.Json)
            setBody("""{"hue":100}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `a user only changes their own colour, not the partner's`() = testApplication {
        configureTestApplication()
        val alice = tokenFor("alice", "password123")

        setColor(alice, """{"hue":300}""")

        val users = usersJson(alice)
        assertEquals(300, users.hueOf("alice")?.jsonPrimitive?.int)
        // bob is untouched — his hue is still automatic (field omitted).
        assertNull(users.hueOf("bob"))
        assertTrue(users.size >= 2)
    }
}
