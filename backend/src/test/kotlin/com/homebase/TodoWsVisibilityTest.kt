package com.homebase

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the visibility model on the realtime channel: todos (and lists) that live in someone
 * else's private list must never be pushed over the shared `/ws/todos` channel. See issue #52.
 */
class TodoWsVisibilityTest {

    private suspend fun ApplicationTestBuilder.login(username: String, password: String): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createList(token: String, name: String, visibility: String): String {
        val res = client.post("/api/v1/todos/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","visibility":"$visibility"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createTodo(token: String, title: String, listId: String? = null): String {
        val body = if (listId != null) """{"title":"$title","listId":"$listId"}""" else """{"title":"$title"}"""
        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Reads the next WS frame's parsed JSON message, failing the test if none arrives in time. */
    private suspend fun DefaultClientWebSocketSession.nextMessage(): JsonObject {
        val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
        return Json.parseToJsonElement(frame.readText()).jsonObject
    }

    @Test
    fun `mutating a todo in another user's private list sends nothing to the other client`() = testApplication {
        configureTestApplication()
        val alice = login("alice", "password123")
        val bob = login("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/api/v1/ws/todos?token=$bob") {
            // Let the server register this session before any mutation is broadcast.
            delay(300)

            // Bracket a private-list mutation between two shared ones. Broadcasts are ordered, so if
            // the private todo leaked it would arrive as Bob's *second* frame, ahead of "second".
            createTodo(alice, "first")
            createTodo(alice, "secret", listId = secretList)
            createTodo(alice, "second")

            val firstMsg = nextMessage()
            assertEquals("TODO_CREATED", firstMsg["type"]?.jsonPrimitive?.content)
            assertEquals("first", firstMsg["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content)

            val secondMsg = nextMessage()
            assertEquals("TODO_CREATED", secondMsg["type"]?.jsonPrimitive?.content)
            assertEquals(
                "second",
                secondMsg["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content,
                "the private-list todo must never reach the other client",
            )

            // Nothing else (the private todo, its list) should be queued for Bob.
            assertNull(withTimeoutOrNull(500) { incoming.receive() }, "no further frames expected for Bob")
        }
    }

    @Test
    fun `subtask changes on a private-list todo do not reach the other client`() = testApplication {
        configureTestApplication()
        val alice = login("alice", "password123")
        val bob = login("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val secretTodo = createTodo(alice, "secret", listId = secretList)

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/api/v1/ws/todos?token=$bob") {
            delay(300)

            // A subtask on the private todo must stay silent; the trailing shared todo must arrive.
            client.post("/api/v1/todos/$secretTodo/subtasks") {
                bearerAuth(alice)
                contentType(ContentType.Application.Json)
                setBody("""{"title":"hidden step"}""")
            }
            createTodo(alice, "visible")

            val msg = nextMessage()
            assertEquals("TODO_CREATED", msg["type"]?.jsonPrimitive?.content)
            assertEquals(
                "visible",
                msg["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content,
                "the subtask update on a private todo must not be broadcast",
            )
        }
    }

    @Test
    fun `flipping a shared list to private tells the other client to drop it`() = testApplication {
        configureTestApplication()
        val alice = login("alice", "password123")
        val bob = login("bob", "password456")
        val list = createList(alice, "Projekt", "SHARED")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/api/v1/ws/todos?token=$bob") {
            delay(300)

            client.put("/api/v1/todos/lists/$list") {
                bearerAuth(alice)
                contentType(ContentType.Application.Json)
                setBody("""{"visibility":"PRIVATE"}""")
            }

            val msg = nextMessage()
            assertEquals("TODO_LIST_DELETED", msg["type"]?.jsonPrimitive?.content)
            assertEquals(list, msg["payload"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `creating a private list is not broadcast to the other client`() = testApplication {
        configureTestApplication()
        val alice = login("alice", "password123")
        val bob = login("bob", "password456")

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/api/v1/ws/todos?token=$bob") {
            delay(300)

            createList(alice, "Geheim", "PRIVATE")
            createTodo(alice, "visible") // a shared todo that *should* arrive

            val msg = nextMessage()
            assertTrue(
                msg["type"]?.jsonPrimitive?.content == "TODO_CREATED" &&
                    msg["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content == "visible",
                "the private list's creation must not precede the shared todo on the channel",
            )

            // The private list's TODO_LIST_CREATED must not be lingering behind the shared todo.
            assertNull(withTimeoutOrNull(500) { incoming.receive() }, "no further frames expected for Bob")
        }
    }
}
