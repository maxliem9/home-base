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
        // assignee/dueDate present on create ⇒ the todo is born PLANNED (quick-add "all-at-once" flow)
        assertEquals("PLANNED", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST todo with a due date but no assignee is created PLANNED`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Zahnarzt","dueDate":"2026-07-01"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("PLANNED", body["status"]?.jsonPrimitive?.content)
        assertEquals("2026-07-01", body["dueDate"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST todo with only a description or priority stays in the inbox`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        // description + priority alone can't satisfy PLANNED (needs assignee or dueDate) → INBOX,
        // but the fields are still stored.
        val response = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Idee","description":"später ausarbeiten","priority":"LOW"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INBOX", body["status"]?.jsonPrimitive?.content)
        assertEquals("später ausarbeiten", body["description"]?.jsonPrimitive?.content)
        assertEquals("LOW", body["priority"]?.jsonPrimitive?.content)
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

    private suspend fun ApplicationTestBuilder.createList(token: String, name: String, visibility: String? = null): String {
        val body = if (visibility != null) """{"name":"$name","visibility":"$visibility"}""" else """{"name":"$name"}"""
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
            setBody("""{"name":"Haushalt","visibility":"PRIVATE"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("Haushalt", body["name"]?.jsonPrimitive?.content)
        assertEquals("PRIVATE", body["visibility"]?.jsonPrimitive?.content)
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
    fun `POST list with invalid visibility returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos/lists") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Arbeit","visibility":"public"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `private list is hidden from the other user`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        createList(alice, "Geheim", "PRIVATE")

        val aliceLists = Json.parseToJsonElement(
            client.get("/api/v1/todos/lists") { bearerAuth(alice) }.bodyAsText()
        ).jsonArray
        assertEquals(1, aliceLists.size)

        val bobLists = Json.parseToJsonElement(
            client.get("/api/v1/todos/lists") { bearerAuth(bob) }.bodyAsText()
        ).jsonArray
        assertTrue(bobLists.isEmpty())
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
    fun `other user cannot rename a private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val listId = createList(alice, "Geheim", "PRIVATE")

        // Bob knows the UUID but must not be able to touch Alice's private list (404, not 403,
        // so its existence stays hidden).
        val res = client.put("/api/v1/todos/lists/$listId") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Hijacked"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // The list is unchanged for its owner.
        val name = Json.parseToJsonElement(
            client.get("/api/v1/todos/lists") { bearerAuth(alice) }.bodyAsText()
        ).jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content
        assertEquals("Geheim", name)
    }

    @Test
    fun `other user cannot delete a private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val listId = createList(alice, "Geheim", "PRIVATE")

        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/v1/todos/lists/$listId") { bearerAuth(bob) }.status,
        )
        assertEquals(
            1,
            Json.parseToJsonElement(
                client.get("/api/v1/todos/lists") { bearerAuth(alice) }.bodyAsText()
            ).jsonArray.size,
        )
    }

    @Test
    fun `other user can still edit a shared list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val listId = createList(alice, "Haushalt", "SHARED")

        val res = client.put("/api/v1/todos/lists/$listId") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Wohnung"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Wohnung", Json.parseToJsonElement(res.bodyAsText()).jsonObject["name"]?.jsonPrimitive?.content)
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
    fun `POST todo with unknown listId returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        // an unknown list answers exactly like a foreign private one (404), so the two are
        // indistinguishable and the private-list existence stays hidden (see #73).
        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"X","listId":"00000000-0000-0000-0000-999999999999"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
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
    fun `PUT todo can set and clear dueDate, priority and assignee`() = testApplication {
        // Regression for #265: the Android edit sheet sends "" to clear an optional field and a
        // value to set it. null (absent) must stay "unchanged"; "" must clear to null; a value sets.
        configureTestApplication()
        val token = loginAndGetToken()
        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Zahnarzt"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // set all three
        val set = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"PLANNED","dueDate":"2026-07-01","priority":"HIGH","assignee":"bob"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        Json.parseToJsonElement(set.bodyAsText()).jsonObject.let {
            assertEquals("2026-07-01", it["dueDate"]?.jsonPrimitive?.content)
            assertEquals("HIGH", it["priority"]?.jsonPrimitive?.content)
            assertEquals("bob", it["assignee"]?.jsonPrimitive?.content)
        }

        // a partial update with the other fields ABSENT must not wipe them (the reported bug)
        val partial = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"priority":"LOW"}""")
        }
        Json.parseToJsonElement(partial.bodyAsText()).jsonObject.let {
            assertEquals("LOW", it["priority"]?.jsonPrimitive?.content)
            assertEquals("2026-07-01", it["dueDate"]?.jsonPrimitive?.content) // untouched
            assertEquals("bob", it["assignee"]?.jsonPrimitive?.content)       // untouched
        }

        // clear each via empty string; assignee/dueDate gone → fall back to INBOX
        val cleared = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"INBOX","dueDate":"","priority":"","assignee":""}""")
        }
        assertEquals(HttpStatusCode.OK, cleared.status)
        Json.parseToJsonElement(cleared.bodyAsText()).jsonObject.let {
            assertTrue(it["dueDate"].isNullJson())
            assertTrue(it["priority"].isNullJson())
            assertTrue(it["assignee"].isNullJson())
        }
    }

    @Test
    fun `DELETE list deletes its todos and their subtasks, leaving other lists untouched`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val listId = createList(token, "Arbeit")
        val keepListId = createList(token, "Privat")

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Report","listId":"$listId"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        // give it a subtask so we cover the subtask cascade too
        client.post("/api/v1/todos/$id/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Teilschritt"}""")
        }
        // a todo in another list must survive the delete
        val survivor = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Bleibt","listId":"$keepListId"}""")
        }
        val survivorId = Json.parseToJsonElement(survivor.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/todos/lists/$listId") { bearerAuth(token) }.status,
        )

        val todos = Json.parseToJsonElement(
            client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        // the list's todo is gone, not merely detached
        assertTrue(todos.none { it.jsonObject["id"]?.jsonPrimitive?.content == id })
        // the unrelated todo is still there
        assertTrue(todos.any { it.jsonObject["id"]?.jsonPrimitive?.content == survivorId })
    }

    // ---- Cross-tenant writes into a foreign private list (issue #73) ----
    // A private list belongs to its creator. Knowing (or guessing) its UUID must not let the other
    // user write a todo into it, move a todo into it, or touch a todo/subtask already inside it. Every
    // such attempt answers 404 — exactly like an unknown id — so no cross-tenant write and no oracle
    // distinguishing "foreign private list/todo exists" from "does not exist".

    private suspend fun ApplicationTestBuilder.todosOf(token: String) = Json.parseToJsonElement(
        client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
    ).jsonArray

    @Test
    fun `other user cannot create a todo in a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")

        val res = client.post("/api/v1/todos") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"injected","listId":"$secretList"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // the owner must not find an injected todo in her private list
        assertTrue(todosOf(alice).isEmpty(), "no todo may have been written into the private list")
    }

    @Test
    fun `other user cannot move a todo into a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val bobTodo = createTodo(bob, "Bob's todo")

        val res = client.put("/api/v1/todos/$bobTodo") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"listId":"$secretList"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // the move must not have happened: Bob's todo still has no list
        val moved = todosOf(bob).single { it.jsonObject["id"]?.jsonPrimitive?.content == bobTodo }
        assertTrue(moved.jsonObject["listId"].isNullJson(), "the todo must not have been moved into the private list")
    }

    @Test
    fun `other user cannot modify a todo in a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val secretTodo = createTodoInList(alice, "secret", secretList)

        val res = client.put("/api/v1/todos/$secretTodo") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Hijacked"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // unchanged for the owner
        val title = todosOf(alice).single().jsonObject["title"]?.jsonPrimitive?.content
        assertEquals("secret", title)
    }

    @Test
    fun `other user cannot delete a todo in a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val secretTodo = createTodoInList(alice, "secret", secretList)

        val res = client.delete("/api/v1/todos/$secretTodo") { bearerAuth(bob) }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // still there for the owner
        assertEquals(1, todosOf(alice).size)
    }

    @Test
    fun `other user cannot add a subtask to a todo in a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val secretTodo = createTodoInList(alice, "secret", secretList)

        val res = client.post("/api/v1/todos/$secretTodo/subtasks") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"injected step"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // the owner's todo gained no subtask
        val subs = todosOf(alice).single().jsonObject["subtasks"]
        assertTrue(subs == null || subs is JsonNull || subs.jsonArray.isEmpty())
    }

    @Test
    fun `other user cannot update a subtask in a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val secretTodo = createTodoInList(alice, "secret", secretList)
        val subId = createSubtask(alice, secretTodo, "step")

        val res = client.put("/api/v1/todos/$secretTodo/subtasks/$subId") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // unchanged for the owner: the subtask is still not done
        val sub = todosOf(alice).single().jsonObject["subtasks"]!!.jsonArray.single().jsonObject
        assertEquals(false, sub["done"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `other user cannot delete a subtask in a foreign private list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val secretList = createList(alice, "Geheim", "PRIVATE")
        val secretTodo = createTodoInList(alice, "secret", secretList)
        val subId = createSubtask(alice, secretTodo, "step")

        val res = client.delete("/api/v1/todos/$secretTodo/subtasks/$subId") { bearerAuth(bob) }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // still there for the owner
        assertEquals(1, todosOf(alice).single().jsonObject["subtasks"]!!.jsonArray.size)
    }

    @Test
    fun `other user can still add a todo to a shared list`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val sharedList = createList(alice, "Haushalt", "SHARED")

        // ownership is only enforced for PRIVATE lists; shared lists stay writable by both users
        val res = client.post("/api/v1/todos") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Einkaufen","listId":"$sharedList"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals(sharedList, Json.parseToJsonElement(res.bodyAsText()).jsonObject["listId"]?.jsonPrimitive?.content)
    }

    // ---- Subtasks ----

    private suspend fun ApplicationTestBuilder.createTodoInList(token: String, title: String, listId: String): String {
        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"$title","listId":"$listId"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createSubtask(token: String, todoId: String, title: String): String {
        val res = client.post("/api/v1/todos/$todoId/subtasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"$title"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText())
            .jsonObject["subtasks"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.content
    }

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

    // ---- Recurring todos ----

    @Test
    fun `POST todo with recurrence stores the rule`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Müll rausbringen","dueDate":"2026-06-15","recurrence":{"freq":"WEEKLY","interval":2}}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val rec = Json.parseToJsonElement(res.bodyAsText()).jsonObject["recurrence"]!!.jsonObject
        assertEquals("WEEKLY", rec["freq"]?.jsonPrimitive?.content)
        assertEquals(2, rec["interval"]?.jsonPrimitive?.int)
    }

    @Test
    fun `POST recurring todo without a dueDate returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Ohne Anker","recurrence":{"freq":"DAILY"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `POST recurring todo with invalid frequency returns 400`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val res = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"X","dueDate":"2026-06-15","recurrence":{"freq":"YEARLY"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `completing a recurring todo spawns the next instance and clears the rule on the done one`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        // a future anchor makes the successor due date deterministic (anchor + 1 week)
        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Müll","dueDate":"2999-01-01","recurrence":{"freq":"WEEKLY"}}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val done = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DONE"}""")
        }
        assertEquals(HttpStatusCode.OK, done.status)
        val doneBody = Json.parseToJsonElement(done.bodyAsText()).jsonObject
        assertEquals("DONE", doneBody["status"]?.jsonPrimitive?.content)
        // the completed instance is now plain history — its recurrence rule moved to the successor
        assertTrue(doneBody["recurrence"].isNullJson())

        val todos = Json.parseToJsonElement(
            client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(2, todos.size)
        val successor = todos.single { it.jsonObject["id"]?.jsonPrimitive?.content != id }.jsonObject
        assertEquals("PLANNED", successor["status"]?.jsonPrimitive?.content)
        assertEquals("2999-01-08", successor["dueDate"]?.jsonPrimitive?.content)
        assertEquals("WEEKLY", successor["recurrence"]?.jsonObject?.get("freq")?.jsonPrimitive?.content)
    }

    @Test
    fun `the spawned recurrence instance carries the subtasks unchecked`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Wochenputz","dueDate":"2999-01-01","recurrence":{"freq":"WEEKLY"}}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val subId = createSubtask(token, id, "Bad")
        // tick the subtask done on the original
        client.put("/api/v1/todos/$id/subtasks/$subId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }

        client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DONE"}""")
        }

        val todos = Json.parseToJsonElement(
            client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        val successor = todos.single { it.jsonObject["id"]?.jsonPrimitive?.content != id }.jsonObject
        val subs = successor["subtasks"]!!.jsonArray
        assertEquals(1, subs.size)
        assertEquals("Bad", subs[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals(false, subs[0].jsonObject["done"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `PUT recurrence freq NONE clears the rule`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"X","dueDate":"2999-01-01","recurrence":{"freq":"WEEKLY"}}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val cleared = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"recurrence":{"freq":"NONE"}}""")
        }
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertTrue(Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["recurrence"].isNullJson())
    }

    @Test
    fun `PUT clearing the dueDate of a recurring todo is rejected and keeps the anchor`() = testApplication {
        // Regression for the recurrence invariant (DB CHECK todos_ln_due_chk + INVALID_RECURRENCE):
        // the #265 "" = clear semantics must not be allowed to strip a recurring todo of its
        // dueDate anchor. The request is rejected (400 INVALID_RECURRENCE) and the anchor stays.
        configureTestApplication()
        val token = loginAndGetToken()

        val created = client.post("/api/v1/todos") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Müll","dueDate":"2999-01-01","recurrence":{"freq":"WEEKLY"}}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val res = client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"dueDate":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(
            "INVALID_RECURRENCE",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )

        // the stored todo still has its anchor: the rejected clear left it untouched
        val stored = todosOf(token).single { it.jsonObject["id"]?.jsonPrimitive?.content == id }.jsonObject
        assertEquals("2999-01-01", stored["dueDate"]?.jsonPrimitive?.content)
        assertEquals("WEEKLY", stored["recurrence"]?.jsonObject?.get("freq")?.jsonPrimitive?.content)
    }

    @Test
    fun `completing a non-recurring todo does not spawn anything`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val id = createTodo(token, "Einmalig")

        client.put("/api/v1/todos/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DONE"}""")
        }

        val todos = Json.parseToJsonElement(
            client.get("/api/v1/todos") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, todos.size)
    }
}
