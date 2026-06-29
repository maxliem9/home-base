package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PushRouteTest {

    private suspend fun ApplicationTestBuilder.token(user: String, pass: String): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"$pass"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.subscribe(token: String, endpoint: String): HttpResponse =
        client.post("/api/v1/push/subscribe") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"endpoint":"$endpoint","keys":{"p256dh":"pub","auth":"sec"}}""")
        }

    @Test
    fun `subscribe and vapid endpoints require auth`() = testApplication {
        configureTestApplication("webpush.publicKey" to "VAPID_PUB")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/push/vapid-public-key").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/push/subscribe") {
                contentType(ContentType.Application.Json)
                setBody("""{"endpoint":"https://x","keys":{"p256dh":"a","auth":"b"}}""")
            }.status,
        )
    }

    @Test
    fun `vapid-public-key returns the configured key`() = testApplication {
        configureTestApplication("webpush.publicKey" to "VAPID_PUB")
        val res = client.get("/api/v1/push/vapid-public-key") { bearerAuth(token("alice", "password123")) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("VAPID_PUB", Json.parseToJsonElement(res.bodyAsText()).jsonObject["publicKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vapid-public-key is 404 when web push is not configured`() = testApplication {
        configureTestApplication() // no webpush.publicKey
        val res = client.get("/api/v1/push/vapid-public-key") { bearerAuth(token("alice", "password123")) }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("WEB_PUSH_DISABLED", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscribe stores a subscription and is idempotent on the endpoint`() = testApplication {
        configureTestApplication("webpush.publicKey" to "VAPID_PUB")
        val t = token("alice", "password123")

        assertEquals(HttpStatusCode.NoContent, subscribe(t, "https://push/a").status)
        // re-subscribing the same endpoint must not error or duplicate (idempotent upsert)
        assertEquals(HttpStatusCode.NoContent, subscribe(t, "https://push/a").status)
    }

    @Test
    fun `subscribe rejects a missing endpoint with 400`() = testApplication {
        configureTestApplication("webpush.publicKey" to "VAPID_PUB")
        val t = token("alice", "password123")
        val res = client.post("/api/v1/push/subscribe") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody("""{"endpoint":"","keys":{"p256dh":"a","auth":"b"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("INVALID_SUBSCRIPTION", Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete removes a subscription and is idempotent`() = testApplication {
        configureTestApplication("webpush.publicKey" to "VAPID_PUB")
        val t = token("alice", "password123")
        subscribe(t, "https://push/a")
        val del = client.delete("/api/v1/push/subscribe") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody("""{"endpoint":"https://push/a"}""")
        }
        assertEquals(HttpStatusCode.NoContent, del.status)
        // deleting again is a no-op (idempotent)
        val del2 = client.delete("/api/v1/push/subscribe") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody("""{"endpoint":"https://push/a"}""")
        }
        assertEquals(HttpStatusCode.NoContent, del2.status)
    }
}
