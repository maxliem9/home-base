package com.homebase

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web client authenticates the WebSocket by passing the JWT as a subprotocol
 * (`new WebSocket(url, ["bearer", token])`), so it rides on the `Sec-WebSocket-Protocol`
 * handshake header instead of the URL. Guards that the backend reads the token there.
 */
class WsAuthTest {

    private suspend fun ApplicationTestBuilder.login(username: String, password: String): String {
        val res = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `authenticates the websocket via the Sec-WebSocket-Protocol header`() = testApplication {
        configureTestApplication()
        val token = login("alice", "password123")

        val wsClient = createClient { install(WebSockets) }
        var entered = false
        wsClient.webSocket("/api/v1/ws/time", request = {
            header(HttpHeaders.SecWebSocketProtocol, "bearer, $token")
        }) {
            // Reaching the block at all means the 101 handshake succeeded → auth passed.
            entered = true
            // And the server must NOT immediately close it (which is what an auth failure looks like).
            val frame = withTimeoutOrNull(300) { incoming.receiveCatching().getOrNull() }
            assertFalse(frame is Frame.Close, "server closed the authenticated socket")
        }
        assertTrue(entered, "expected the WS handshake to succeed with the subprotocol token")
    }

    @Test
    fun `rejects the websocket when no credential is supplied`() = testApplication {
        configureTestApplication()

        val wsClient = createClient { install(WebSockets) }
        var rejected = false
        try {
            // No Authorization header, no subprotocol, no ?token → auth must fail the upgrade.
            wsClient.webSocket("/api/v1/ws/time") { }
        } catch (_: Throwable) {
            rejected = true
        }
        assertTrue(rejected, "expected the unauthenticated WS handshake to be rejected")
    }
}
