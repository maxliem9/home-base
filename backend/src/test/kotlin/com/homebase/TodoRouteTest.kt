package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoRouteTest {

    private suspend fun ApplicationTestBuilder.loginAndGetToken(
        username: String = "alice",
        password: String = "password123",
    ): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `GET todos without token returns 401`() = testApplication {
        configureTestApplication()

        val response = client.get("/api/v1/todos")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET todos with token returns empty list initially`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.get("/api/v1/todos") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `POST todo creates inbox item`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Buy groceries"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Buy groceries", body["title"]?.jsonPrimitive?.content)
        assertEquals("INBOX", body["status"]?.jsonPrimitive?.content)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST todo with all optional fields stores them`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"title":"Plan trip","description":"Weekend getaway","assignee":"bob","dueDate":"2026-06-15","priority":"HIGH"}"""
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Plan trip", body["title"]?.jsonPrimitive?.content)
        assertEquals("Weekend getaway", body["description"]?.jsonPrimitive?.content)
        assertEquals("bob", body["assignee"]?.jsonPrimitive?.content)
        assertEquals("2026-06-15", body["dueDate"]?.jsonPrimitive?.content)
        assertEquals("HIGH", body["priority"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT todo updates title and status`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Original title"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Updated title","status":"PLANNED"}""")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals("Updated title", body["title"]?.jsonPrimitive?.content)
        assertEquals("PLANNED", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT todo to DONE sets doneAt timestamp`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Finish report"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DONE"}""")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals("DONE", body["status"]?.jsonPrimitive?.content)
        assertTrue(body["doneAt"]?.jsonPrimitive?.content?.isNotBlank() == true)
    }

    @Test
    fun `PUT unknown todo returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.put("/api/v1/todos/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Ghost"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE todo removes it from the list`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Temporary"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/todos/$id") { bearerAuth(token) }.status)
        assertTrue(
            Json.parseToJsonElement(
                client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
            ).jsonArray.isEmpty()
        )
    }

    @Test
    fun `DELETE unknown todo returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.delete("/api/v1/todos/00000000-0000-0000-0000-999999999999") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET todos returns todos from both users`() = testApplication {
        configureTestApplication()
        val aliceToken = loginAndGetToken("alice", "password123")
        val bobToken = loginAndGetToken("bob", "password456")

        client.post("/api/v1/todos") {
            bearerAuth(aliceToken)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Alice's todo"}""")
        }
        client.post("/api/v1/todos") {
            bearerAuth(bobToken)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Bob's todo"}""")
        }

        val todos = Json.parseToJsonElement(
            client.get("/api/v1/todos") { bearerAuth(aliceToken) }.bodyAsText()
        ).jsonArray
        assertEquals(2, todos.size)
    }
}
