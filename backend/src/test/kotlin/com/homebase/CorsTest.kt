package com.homebase

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CORS origin pinning: with app.domain set (prod: DOMAIN env) only https://<domain>
 * may act as a browser origin; unset keeps the permissive dev behaviour. Auth is
 * header-based (no cookies), so this is defense in depth — a leaked token must not
 * be replayable from arbitrary websites.
 */
class CorsTest {

    @Test
    fun `preflight from the pinned origin is allowed`() = testApplication {
        configureTestApplication("app.domain" to "homebase.example.com")
        val res = client.options("/api/v1/users") {
            header(HttpHeaders.Origin, "https://homebase.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals("https://homebase.example.com", res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `preflight from a foreign origin is rejected`() = testApplication {
        configureTestApplication("app.domain" to "homebase.example.com")
        val res = client.options("/api/v1/users") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertNull(res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `a scheme or trailing slash in DOMAIN is tolerated`() = testApplication {
        configureTestApplication("app.domain" to "https://homebase.example.com/")
        val res = client.options("/api/v1/users") {
            header(HttpHeaders.Origin, "https://homebase.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals("https://homebase.example.com", res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `without a domain any origin stays allowed (dev)`() = testApplication {
        configureTestApplication()
        val res = client.options("/api/v1/users") {
            header(HttpHeaders.Origin, "https://anything.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertNotNull(res.headers[HttpHeaders.AccessControlAllowOrigin], "anyHost() should allow arbitrary origins in dev")
    }
}
