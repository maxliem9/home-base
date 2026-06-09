package com.homebase

import com.homebase.security.LoginThrottler
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRouteTest {

    @Test
    fun `POST login with valid credentials returns token`() = testApplication {
        configureTestApplication()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"password123"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.containsKey("token"))
        assertTrue(body["token"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `POST login with wrong password returns 401`() = testApplication {
        configureTestApplication()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"wrongpassword"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INVALID_CREDENTIALS", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST login with unknown user returns 401`() = testApplication {
        configureTestApplication()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"nobody","password":"anything"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `repeated failures from one source get throttled with 429 and Retry-After`() = testApplication {
        configureTestApplication()

        // Exhaust the free attempts (each a genuine 401), then the next request is locked out.
        repeat(LoginThrottler.DEFAULT_MAX_FAILURES) {
            val r = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"wrongpassword"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

        val locked = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"wrongpassword"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        val body = Json.parseToJsonElement(locked.bodyAsText()).jsonObject
        assertEquals("TOO_MANY_ATTEMPTS", body["code"]?.jsonPrimitive?.content)
        assertTrue((locked.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0) > 0)
    }

    @Test
    fun `a throttled source is blocked even with the correct password`() = testApplication {
        configureTestApplication()

        repeat(LoginThrottler.DEFAULT_MAX_FAILURES) {
            client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"wrongpassword"}""")
            }
        }

        // The throttle fires before the password check, so a sudden correct guess can't slip past
        // an active lockout — this is what makes online brute force pointless.
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"password123"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `X-Forwarded-For spoof prefix does not move the throttle bucket`() = testApplication {
        configureTestApplication()

        // Prod default trustedProxyCount = 2, so in "spoof, client, proxy" the real client is the
        // middle entry; the leftmost is attacker-supplied and must not change the bucket.
        suspend fun attempt(xff: String, password: String) = client.post("/api/v1/auth/login") {
            header("X-Forwarded-For", xff)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"$password"}""")
        }

        repeat(LoginThrottler.DEFAULT_MAX_FAILURES) {
            attempt("9.9.9.9, 203.0.113.7, 10.0.0.1", "wrongpassword")
        }

        // Same real client, different spoofed prefix → still locked.
        assertEquals(HttpStatusCode.TooManyRequests, attempt("8.8.8.8, 203.0.113.7, 10.0.0.1", "x").status)
        // A genuinely different client → independent bucket, still allowed (plain 401).
        assertEquals(HttpStatusCode.Unauthorized, attempt("9.9.9.9, 198.51.100.4, 10.0.0.1", "x").status)
    }
}
