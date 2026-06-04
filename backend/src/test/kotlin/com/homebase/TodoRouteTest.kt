package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A field is "null" when it is either serialized as JSON null or omitted entirely
// (kotlinx-serialization drops null/empty defaults from the payload).
private fun JsonElement?.isNullJson(): Boolean = this == null || this is JsonNull

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
    fun `POST todo with blank title returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
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
            setBody("""{"title":"Updated title","status":"PLANNED","assignee":"bob"}""")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals("Updated title", body["title"]?.jsonPrimitive?.content)
        assertEquals("PLANNED", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT todo to PLANNED without assignee or due date returns 400`() = testApplication {
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
            setBody("""{"status":"PLANNED"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, updated.status)
    }

    @Test
    fun `PUT todo with invalid status returns 400`() = testApplication {
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
            setBody("""{"status":"LATER"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, updated.status)
    }

    @Test
    fun `POST todo with malformed dueDate returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Termin","dueDate":"tomorrow"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT todo with malformed id returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.put("/api/v1/todos/not-a-uuid") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"X"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
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

    // ---- Lists ----

    private suspend fun ApplicationTestBuilder.createList(token: String, name: String, color: String? = null): String {
        val body = if (color != null) """{"name":"$name","color":"$color"}""" else """{"name":"$name"}"""
        val res = client.post("/api/v1/todos/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `POST list creates it and GET lists returns it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Haushalt","color":"#ff0000"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("Haushalt", body["name"]?.jsonPrimitive?.content)
        assertEquals("#ff0000", body["color"]?.jsonPrimitive?.content)
        assertEquals("alice", body["createdBy"]?.jsonPrimitive?.content)

        val lists = Json.parseToJsonElement(
            client.get("/api/v1/todos/lists") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, lists.size)
    }

    @Test
    fun `POST list with blank name returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `POST list with invalid color returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Arbeit","color":"red"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PUT list renames it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Verein")

        val res = client.put("/api/v1/todos/lists/$listId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Sportverein"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Sportverein", Json.parseToJsonElement(res.bodyAsText()).jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST todo with listId stores it`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Kind")

        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Windeln kaufen","listId":"$listId"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals(listId, Json.parseToJsonElement(res.bodyAsText()).jsonObject["listId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST todo with unknown listId returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"X","listId":"00000000-0000-0000-0000-999999999999"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PUT todo can assign and clear a list`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Haushalt")
        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Müll rausbringen"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assigned = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"listId":"$listId"}""")
        }
        assertEquals(listId, Json.parseToJsonElement(assigned.bodyAsText()).jsonObject["listId"]?.jsonPrimitive?.content)

        val cleared = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"listId":""}""")
        }
        // null defaults are omitted from the JSON, so an absent key means "no list"
        assertTrue(Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["listId"].isNullJson())
    }

    @Test
    fun `DELETE list detaches its todos`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Arbeit")
        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Report","listId":"$listId"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/todos/lists/$listId") { bearerAuth(token) }.status,
        )

        val todo = Json.parseToJsonElement(
            client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
        ).jsonArray.single { it.jsonObject["id"]?.jsonPrimitive?.content == id }
        assertTrue(todo.jsonObject["listId"].isNullJson())
    }

    // ---- Subtasks ----

    private suspend fun ApplicationTestBuilder.createTodo(token: String, title: String): String {
        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"$title"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `POST subtask appears in the parent todo`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val todoId = createTodo(token, "Umzug planen")

        val res = client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Kartons besorgen"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val subtasks = Json.parseToJsonElement(res.bodyAsText()).jsonObject["subtasks"]!!.jsonArray
        assertEquals(1, subtasks.size)
        assertEquals("Kartons besorgen", subtasks[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals(false, subtasks[0].jsonObject["done"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `POST subtask with blank title returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val todoId = createTodo(token, "Parent")

        val res = client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `PUT subtask toggles done`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val todoId = createTodo(token, "Parent")
        val created = client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Step 1"}""")
        }
        val subId = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["subtasks"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val res = client.put("/api/v1/todos/$todoId/subtasks/$subId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val sub = Json.parseToJsonElement(res.bodyAsText()).jsonObject["subtasks"]!!.jsonArray[0].jsonObject
        assertEquals(true, sub["done"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `DELETE subtask removes it from the parent`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val todoId = createTodo(token, "Parent")
        val created = client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Step 1"}""")
        }
        val subId = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["subtasks"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val res = client.delete("/api/v1/todos/$todoId/subtasks/$subId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        // empty list default is omitted from the JSON
        val subs = Json.parseToJsonElement(res.bodyAsText()).jsonObject["subtasks"]
        assertTrue(subs == null || subs is JsonNull || subs.jsonArray.isEmpty())
    }

    @Test
    fun `DELETE todo also removes its subtasks`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val todoId = createTodo(token, "Parent")
        client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Step 1"}""")
        }

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/todos/$todoId") { bearerAuth(token) }.status)
        // recreating subtask on the deleted parent must 404 (parent and its subtasks are gone)
        val res = client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Ghost"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
