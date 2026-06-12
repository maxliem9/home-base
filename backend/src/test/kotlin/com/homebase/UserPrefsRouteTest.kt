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

class UserPrefsRouteTest {

    private suspend fun ApplicationTestBuilder.token(user: String, pass: String): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"$pass"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.prefs(token: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/user-prefs") { bearerAuth(token) }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.putPref(token: String, key: String, value: String): HttpResponse =
        client.put("/api/v1/user-prefs/$key") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"value":"$value"}""")
        }

    @Test
    fun `GET user-prefs without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/user-prefs").status)
    }

    @Test
    fun `PUT user-pref without token returns 401`() = testApplication {
        configureTestApplication()
        val res = client.put("/api/v1/user-prefs/theme") {
            contentType(ContentType.Application.Json); setBody("""{"value":"dark"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `GET user-prefs is an empty object when nothing is set`() = testApplication {
        configureTestApplication()
        assertTrue(prefs(token("alice", "password123")).isEmpty())
    }

    @Test
    fun `PUT then GET round-trips a pref and the PUT echoes the full map`() = testApplication {
        configureTestApplication()
        val t = token("alice", "password123")

        val res = putPref(t, "theme", "dark")
        assertEquals(HttpStatusCode.OK, res.status)
        // the PUT returns the full updated map
        assertEquals("dark", Json.parseToJsonElement(res.bodyAsText()).jsonObject["theme"]!!.jsonPrimitive.content)
        // and a fresh GET reflects it
        assertEquals("dark", prefs(t)["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second PUT overwrites rather than inserting a duplicate`() = testApplication {
        configureTestApplication()
        val t = token("alice", "password123")
        putPref(t, "theme", "dark")
        putPref(t, "theme", "light")
        val map = prefs(t)
        assertEquals("light", map["theme"]!!.jsonPrimitive.content)
        assertEquals(1, map.size)
    }

    @Test
    fun `multiple keys coexist for one user`() = testApplication {
        configureTestApplication()
        val t = token("alice", "password123")
        putPref(t, "theme", "system")
        putPref(t, "density", "compact")
        val map = prefs(t)
        assertEquals("system", map["theme"]!!.jsonPrimitive.content)
        assertEquals("compact", map["density"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prefs are per-user — one user cannot see or clobber another's`() = testApplication {
        configureTestApplication()
        val alice = token("alice", "password123")
        val bob = token("bob", "password456")

        putPref(alice, "theme", "dark")
        // bob has his own (empty) namespace
        assertTrue(prefs(bob).isEmpty())

        // bob sets the same key to a different value — alice is untouched
        putPref(bob, "theme", "light")
        assertEquals("dark", prefs(alice)["theme"]!!.jsonPrimitive.content)
        assertEquals("light", prefs(bob)["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT rejects a blank key with 400 INVALID_KEY`() = testApplication {
        configureTestApplication()
        val t = token("alice", "password123")
        // a whitespace-only key in the path trims to empty
        val res = client.put("/api/v1/user-prefs/%20") {
            bearerAuth(t); contentType(ContentType.Application.Json); setBody("""{"value":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("INVALID_KEY", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT rejects an over-long value with 400 VALUE_TOO_LONG`() = testApplication {
        configureTestApplication()
        val t = token("alice", "password123")
        val res = putPref(t, "theme", "x".repeat(4097))
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("VALUE_TOO_LONG", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
        // nothing was persisted
        assertNull(prefs(t)["theme"])
    }
}
