package com.homebase

import com.homebase.db.TodoAssigneesTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.recurrence.Recurrence
import com.homebase.recurrence.RecurrenceSpawner
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Unit tests for the completion-driven recurrence successor (issue #546): anchor/interval date math,
 * the unchecked subtask reset and assignee inheritance — exercised directly against the DB, without
 * an HTTP round-trip. The pure date helpers live in [RecurrenceTest]; this covers the row the spawner
 * actually writes.
 */
class RecurrenceSpawnerTest {

    private val spawner = RecurrenceSpawner()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:spawner_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(TodoListsTable, TodosTable, TodoSubtasksTable, TodoAssigneesTable) }
    }

    /** Inserts a "completed" source todo the spawner copies from; returns its id. */
    private fun insertSource(
        dueDate: LocalDate,
        freq: String = Recurrence.WEEKLY,
        interval: Int = 1,
        subtasks: List<Pair<String, Boolean>> = emptyList(),
        assignees: List<String> = emptyList(),
    ): UUID = transaction {
        val id = UUID.randomUUID()
        TodosTable.insert {
            it[TodosTable.id] = id
            it[title] = "Müll rausbringen"
            it[status] = "DONE"
            it[TodosTable.dueDate] = dueDate
            it[recurrence] = freq
            it[recurrenceInterval] = interval
            it[createdBy] = "alice"
            it[createdAt] = Instant.now()
        }
        subtasks.forEachIndexed { i, (title, done) ->
            TodoSubtasksTable.insert {
                it[TodoSubtasksTable.id] = UUID.randomUUID()
                it[todoId] = id
                it[TodoSubtasksTable.title] = title
                it[TodoSubtasksTable.done] = done
                it[sortOrder] = i
                it[createdAt] = Instant.now()
            }
        }
        assignees.forEach { u ->
            TodoAssigneesTable.insert {
                it[todoId] = id
                it[username] = u
            }
        }
        id
    }

    private fun row(id: UUID) = transaction {
        TodosTable.selectAll().where { TodosTable.id eq id }.single()
    }

    private fun baseSpec(sourceId: UUID, anchor: LocalDate, freq: String, interval: Int) =
        RecurrenceSpawner.Spec(
            sourceTodoId = sourceId,
            title = "Müll rausbringen",
            description = null,
            dueTime = null,
            reminderLeadMinutes = null,
            priority = null,
            listId = null,
            freq = freq,
            interval = interval,
            anchorDueDate = anchor,
            createdBy = "alice",
            assignees = emptyList(),
        )

    @Test
    fun `spawns the successor one period on for an on-time weekly completion`() {
        val anchor = LocalDate.of(2026, 6, 8) // Monday
        val src = insertSource(dueDate = anchor)
        val newId = transaction {
            spawner.spawn(baseSpec(src, anchor, Recurrence.WEEKLY, 1), today = anchor)
        }
        val r = row(newId)
        assertEquals(LocalDate.of(2026, 6, 15), r[TodosTable.dueDate])
        assertEquals("PLANNED", r[TodosTable.status])
        assertEquals(Recurrence.WEEKLY, r[TodosTable.recurrence])
        assertEquals(1, r[TodosTable.recurrenceInterval])
        assertNull(r[TodosTable.doneAt])
    }

    @Test
    fun `skips fully-elapsed periods so a long-overdue successor still lands in the future`() {
        val anchor = LocalDate.of(2026, 6, 1)
        val src = insertSource(dueDate = anchor)
        val newId = transaction {
            spawner.spawn(baseSpec(src, anchor, Recurrence.WEEKLY, 1), today = LocalDate.of(2026, 6, 20))
        }
        // first weekly occurrence strictly after the 20th, same weekday as the anchor
        assertEquals(LocalDate.of(2026, 6, 22), row(newId)[TodosTable.dueDate])
    }

    @Test
    fun `carries the every-N interval onto the successor and anchors the day-of-month`() {
        val anchor = LocalDate.of(2026, 1, 31)
        val src = insertSource(dueDate = anchor, freq = Recurrence.MONTHLY, interval = 2)
        val newId = transaction {
            spawner.spawn(baseSpec(src, anchor, Recurrence.MONTHLY, 2), today = anchor)
        }
        val r = row(newId)
        // Jan 31 + 2 months = Mar 31 (clamped per absolute offset, no 28th drift), not Feb
        assertEquals(LocalDate.of(2026, 3, 31), r[TodosTable.dueDate])
        assertEquals(2, r[TodosTable.recurrenceInterval])
    }

    @Test
    fun `copies subtasks unchecked, in order, with fresh ids`() {
        val anchor = LocalDate.of(2026, 6, 8)
        val src = insertSource(
            dueDate = anchor,
            subtasks = listOf("Tonne raus" to true, "Deckel zu" to false),
        )
        val newId = transaction {
            spawner.spawn(baseSpec(src, anchor, Recurrence.WEEKLY, 1), today = anchor)
        }
        val subs = transaction {
            TodoSubtasksTable.selectAll().where { TodoSubtasksTable.todoId eq newId }
                .orderBy(TodoSubtasksTable.sortOrder to SortOrder.ASC)
                .map { Triple(it[TodoSubtasksTable.title], it[TodoSubtasksTable.done], it[TodoSubtasksTable.id]) }
        }
        assertEquals(listOf("Tonne raus", "Deckel zu"), subs.map { it.first })
        assertFalse(subs.any { it.second }) // every copied subtask is reset to unchecked
        // fresh rows: none of the successor's subtask ids equal a source subtask id
        val srcIds = transaction {
            TodoSubtasksTable.selectAll().where { TodoSubtasksTable.todoId eq src }
                .map { it[TodoSubtasksTable.id] }.toSet()
        }
        assertFalse(subs.any { it.third in srcIds })
    }

    @Test
    fun `inherits the assignee set of the completed instance`() {
        val anchor = LocalDate.of(2026, 6, 8)
        val src = insertSource(dueDate = anchor, assignees = listOf("alice", "bob"))
        val newId = transaction {
            spawner.spawn(
                baseSpec(src, anchor, Recurrence.WEEKLY, 1).copy(assignees = listOf("alice", "bob")),
                today = anchor,
            )
        }
        val assignees = transaction {
            TodoAssigneesTable.selectAll().where { TodoAssigneesTable.todoId eq newId }
                .map { it[TodoAssigneesTable.username] }.toSet()
        }
        assertEquals(setOf("alice", "bob"), assignees)
    }

    @Test
    fun `carries due time, reminder, priority and list onto the successor`() {
        val anchor = LocalDate.of(2026, 6, 8)
        val listId = UUID.randomUUID()
        val src = insertSource(dueDate = anchor)
        val newId = transaction {
            spawner.spawn(
                baseSpec(src, anchor, Recurrence.WEEKLY, 1).copy(
                    dueTime = "07:30",
                    reminderLeadMinutes = 15,
                    priority = "HIGH",
                    listId = listId,
                ),
                today = anchor,
            )
        }
        val r = row(newId)
        assertEquals(LocalTime.of(7, 30), r[TodosTable.dueTime])
        assertEquals(15, r[TodosTable.reminderLeadMinutes])
        assertEquals("HIGH", r[TodosTable.priority])
        assertEquals(listId, r[TodosTable.listId])
    }
}
