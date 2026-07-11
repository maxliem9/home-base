package com.homebase

import com.homebase.db.TodoAssigneesTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UsersTable
import com.homebase.model.CreateSubtaskRequest
import com.homebase.model.CreateTodoRequest
import com.homebase.model.RecurrenceDto
import com.homebase.model.UpdateTodoRequest
import com.homebase.service.TodoService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TodoService] (issue #546): the domain logic — validation, the tri-state merge,
 * private-list visibility (#73) and completion-driven recurrence — exercised through the service's
 * sealed result types, without an HTTP layer. The full HTTP contract stays covered by TodoRouteTest.
 */
class TodoServiceTest {

    private val service = TodoService()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:todoservice_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(UsersTable, TodoListsTable, TodosTable, TodoSubtasksTable, TodoAssigneesTable)
            listOf("alice", "bob").forEach { u ->
                UsersTable.insert {
                    it[id] = UUID.randomUUID()
                    it[username] = u
                    it[passwordHash] = "x"
                    it[createdAt] = Instant.now()
                }
            }
        }
    }

    private fun insertList(owner: String, visibility: String): UUID = transaction {
        val id = UUID.randomUUID()
        TodoListsTable.insert {
            it[TodoListsTable.id] = id
            it[name] = "Liste"
            it[TodoListsTable.visibility] = visibility
            it[createdBy] = owner
            it[createdAt] = Instant.now()
        }
        id
    }

    // ---- create: status inference & validation ---------------------------

    @Test
    fun `create with only a title stays in the INBOX`() {
        val r = service.createTodo(CreateTodoRequest(title = "Milch kaufen"), "alice")
        assertTrue(r is TodoService.CreateTodoResult.Ok)
        assertEquals("INBOX", r.mutation.todo.status)
        assertTrue(r.mutation.isShared)
    }

    @Test
    fun `create with a due date is born PLANNED`() {
        val r = service.createTodo(CreateTodoRequest(title = "Zahnarzt", dueDate = "2026-07-20"), "alice")
        assertTrue(r is TodoService.CreateTodoResult.Ok)
        assertEquals("PLANNED", r.mutation.todo.status)
    }

    @Test
    fun `create rejects a blank title as Invalid`() {
        val r = service.createTodo(CreateTodoRequest(title = "  "), "alice")
        assertTrue(r is TodoService.CreateTodoResult.Invalid)
        assertEquals("INVALID_TODO", r.error.code)
    }

    @Test
    fun `create rejects an unknown assignee as Invalid`() {
        val r = service.createTodo(CreateTodoRequest(title = "X", assignees = listOf("carol")), "alice")
        assertTrue(r is TodoService.CreateTodoResult.Invalid)
        assertEquals("INVALID_ASSIGNEE", r.error.code)
    }

    @Test
    fun `create into someone else's private list is NotFound, never a 403 oracle`() {
        val bobsPrivate = insertList(owner = "bob", visibility = "PRIVATE")
        val r = service.createTodo(CreateTodoRequest(title = "X", listId = bobsPrivate.toString()), "alice")
        assertEquals(TodoService.CreateTodoResult.NotFound, r)
    }

    @Test
    fun `create rejects a recurrence without a due-date anchor`() {
        val r = service.createTodo(
            CreateTodoRequest(title = "Müll", recurrence = RecurrenceDto("WEEKLY", 1)),
            "alice",
        )
        assertTrue(r is TodoService.CreateTodoResult.Invalid)
        assertEquals("INVALID_RECURRENCE", r.error.code)
    }

    // ---- update: recurrence completion spawns a successor ----------------

    @Test
    fun `completing a recurring todo spawns a successor and clears the rule on the original`() {
        val created = service.createTodo(
            CreateTodoRequest(title = "Müll", dueDate = "2026-07-06", recurrence = RecurrenceDto("WEEKLY", 1)),
            "alice",
        )
        assertTrue(created is TodoService.CreateTodoResult.Ok)
        val id = UUID.fromString(created.mutation.todo.id)

        val done = service.updateTodo(id, UpdateTodoRequest(status = "DONE"), "alice")
        assertTrue(done is TodoService.UpdateTodoResult.Ok)
        // original becomes plain history: DONE, recurrence cleared
        assertEquals("DONE", done.mutation.todo.status)
        assertNull(done.mutation.todo.recurrence)
        // a fresh successor is spawned, still recurring, one week on, PLANNED
        val spawned = done.mutation.spawned
        assertNotNull(spawned)
        assertEquals("PLANNED", spawned.status)
        assertEquals("2026-07-13", spawned.dueDate)
        assertEquals("WEEKLY", spawned.recurrence?.freq)
    }

    @Test
    fun `updating a todo in a foreign private list is NotFound`() {
        val bobsPrivate = insertList(owner = "bob", visibility = "PRIVATE")
        val created = service.createTodo(
            CreateTodoRequest(title = "Geheim", listId = bobsPrivate.toString()),
            "bob",
        )
        assertTrue(created is TodoService.CreateTodoResult.Ok)
        val id = UUID.fromString(created.mutation.todo.id)

        val r = service.updateTodo(id, UpdateTodoRequest(title = "geändert"), "alice")
        assertTrue(r is TodoService.UpdateTodoResult.NotFound)
        assertEquals("Todo not found", r.message)
    }

    @Test
    fun `moving a todo into an unknown list is NotFound with the list message`() {
        val created = service.createTodo(CreateTodoRequest(title = "X"), "alice")
        assertTrue(created is TodoService.CreateTodoResult.Ok)
        val id = UUID.fromString(created.mutation.todo.id)

        // 404 with "List not found" (not "Todo not found") — the todo exists, the target list doesn't
        val r = service.updateTodo(id, UpdateTodoRequest(listId = UUID.randomUUID().toString()), "alice")
        assertTrue(r is TodoService.UpdateTodoResult.NotFound)
        assertEquals("List not found", r.message)
    }

    @Test
    fun `update leaves the assignee set untouched when the field is absent`() {
        val created = service.createTodo(
            CreateTodoRequest(title = "X", assignees = listOf("alice")),
            "alice",
        )
        assertTrue(created is TodoService.CreateTodoResult.Ok)
        val id = UUID.fromString(created.mutation.todo.id)

        // a title-only edit must not wipe the assignees (null = unchanged)
        val r = service.updateTodo(id, UpdateTodoRequest(title = "Y"), "alice")
        assertTrue(r is TodoService.UpdateTodoResult.Ok)
        assertEquals(listOf("alice"), r.mutation.todo.assignees)
    }

    // ---- subtasks --------------------------------------------------------

    @Test
    fun `adding a blank subtask is Invalid`() {
        val created = service.createTodo(CreateTodoRequest(title = "X"), "alice")
        assertTrue(created is TodoService.CreateTodoResult.Ok)
        val id = UUID.fromString(created.mutation.todo.id)

        val r = service.addSubtask(id, CreateSubtaskRequest(title = "   "), "alice")
        assertTrue(r is TodoService.SubtaskResult.Invalid)
        assertEquals("INVALID_SUBTASK", r.error.code)
    }

    @Test
    fun `adding a subtask to an unknown todo is NotFound`() {
        val r = service.addSubtask(UUID.randomUUID(), CreateSubtaskRequest(title = "Teil"), "alice")
        assertEquals(TodoService.SubtaskResult.NotFound, r)
    }
}
