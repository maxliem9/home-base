package com.homebase

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
}
