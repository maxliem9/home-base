package com.homebase

import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.recurrence.Recurrence
import com.homebase.recurrence.RecurringTodoService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceTest {

    // ---- pure date math --------------------------------------------------

    @Test
    fun `nextOccurrence advances by frequency and interval`() {
        val d = LocalDate.of(2026, 6, 1)
        assertEquals(LocalDate.of(2026, 6, 2), Recurrence.nextOccurrence(d, Recurrence.DAILY, 1))
        assertEquals(LocalDate.of(2026, 6, 8), Recurrence.nextOccurrence(d, Recurrence.WEEKLY, 1))
        assertEquals(LocalDate.of(2026, 6, 15), Recurrence.nextOccurrence(d, Recurrence.WEEKLY, 2))
        assertEquals(LocalDate.of(2026, 7, 1), Recurrence.nextOccurrence(d, Recurrence.MONTHLY, 1))
    }

    @Test
    fun `monthly recurrence clamps to the last valid day`() {
        // Jan 31 + 1 month lands on Feb 28 (2026 is not a leap year)
        assertEquals(
            LocalDate.of(2026, 2, 28),
            Recurrence.nextOccurrence(LocalDate.of(2026, 1, 31), Recurrence.MONTHLY, 1),
        )
    }

    @Test
    fun `nextDueAfterCompletion is one step on for an on-time completion`() {
        val due = LocalDate.of(2026, 6, 8) // a Monday
        val today = LocalDate.of(2026, 6, 8) // completed on the due day
        assertEquals(
            LocalDate.of(2026, 6, 15),
            Recurrence.nextDueAfterCompletion(due, Recurrence.WEEKLY, 1, today),
        )
    }

    @Test
    fun `nextDueAfterCompletion skips elapsed periods so the successor is in the future`() {
        val due = LocalDate.of(2026, 6, 1) // long-overdue weekly anchor
        val today = LocalDate.of(2026, 6, 20)
        val next = Recurrence.nextDueAfterCompletion(due, Recurrence.WEEKLY, 1, today)
        // first weekly occurrence strictly after the 20th, on the same weekday as the anchor
        assertEquals(LocalDate.of(2026, 6, 22), next)
    }

    @Test
    fun `rollOpenDueForward leaves a todo only slightly overdue untouched`() {
        val due = LocalDate.of(2026, 6, 8) // due Monday
        val today = LocalDate.of(2026, 6, 10) // 2 days late, still within the current week
        assertEquals(due, Recurrence.rollOpenDueForward(due, Recurrence.WEEKLY, 1, today))
    }

    @Test
    fun `rollOpenDueForward collapses fully-elapsed periods, keeping the current one`() {
        val due = LocalDate.of(2026, 6, 1) // due 3 Mondays ago
        val today = LocalDate.of(2026, 6, 16)
        // skips the two fully-elapsed weeks; keeps the most recent occurrence (still <= today)
        assertEquals(LocalDate.of(2026, 6, 15), Recurrence.rollOpenDueForward(due, Recurrence.WEEKLY, 1, today))
    }

    // ---- service: roll-forward over the DB -------------------------------

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:recurrence_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(TodosTable, TodoSubtasksTable) }
    }

    private fun insertRecurring(
        title: String,
        dueDate: LocalDate,
        status: String = "PLANNED",
        freq: String? = Recurrence.WEEKLY,
        interval: Int? = 1,
    ): UUID = transaction {
        val id = UUID.randomUUID()
        TodosTable.insert {
            it[TodosTable.id] = id
            it[TodosTable.title] = title
            it[TodosTable.status] = status
            it[TodosTable.dueDate] = dueDate
            it[TodosTable.recurrence] = freq
            it[TodosTable.recurrenceInterval] = interval
            it[TodosTable.createdBy] = "alice"
            it[TodosTable.createdAt] = Instant.now()
        }
        id
    }

    private fun dueOf(id: UUID): LocalDate? = transaction {
        TodosTable.selectAll().where { TodosTable.id eq id }.single()[TodosTable.dueDate]
    }

    @Test
    fun `rollForwardOverdue advances a long-overdue open recurring todo`() {
        val today = LocalDate.of(2026, 6, 16)
        val id = insertRecurring("Müll", dueDate = LocalDate.of(2026, 6, 1)) // 3 weeks back

        val rolled = RecurringTodoService().rollForwardOverdue(today)

        assertEquals(1, rolled.size)
        assertEquals(LocalDate.of(2026, 6, 15), dueOf(id))
    }

    @Test
    fun `rollForwardOverdue leaves recently-due, done, and non-recurring todos alone`() {
        val today = LocalDate.of(2026, 6, 16)
        val recent = insertRecurring("Recent", dueDate = LocalDate.of(2026, 6, 15)) // 1 day late
        val done = insertRecurring("Done", dueDate = LocalDate.of(2026, 6, 1), status = "DONE")
        val oneOff = insertRecurring("OneOff", dueDate = LocalDate.of(2026, 6, 1), freq = null, interval = null)

        val rolled = RecurringTodoService().rollForwardOverdue(today)

        assertEquals(0, rolled.size)
        assertEquals(LocalDate.of(2026, 6, 15), dueOf(recent))
        assertEquals(LocalDate.of(2026, 6, 1), dueOf(done))
        assertEquals(LocalDate.of(2026, 6, 1), dueOf(oneOff))
    }
}
