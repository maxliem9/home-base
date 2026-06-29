package com.homebase

import com.homebase.db.TodosTable
import com.homebase.digest.TelegramClient
import com.homebase.reminder.ReminderService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderServiceTest {

    private class FakeTelegramClient : TelegramClient {
        val messages = mutableListOf<String>()
        override suspend fun sendMessage(text: String) { messages.add(text) }
    }

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:reminder_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(TodosTable) }
    }

    private fun insertTodo(
        title: String,
        dueDate: LocalDate?,
        dueTime: LocalTime?,
        lead: Int? = null,
        status: String = "PLANNED",
        assignee: String? = null,
    ): UUID = transaction {
        val id = UUID.randomUUID()
        TodosTable.insert {
            it[TodosTable.id] = id
            it[TodosTable.title] = title
            it[TodosTable.status] = status
            it[TodosTable.assignee] = assignee
            it[TodosTable.dueDate] = dueDate
            it[TodosTable.dueTime] = dueTime
            it[TodosTable.reminderLeadMinutes] = lead
            it[TodosTable.createdBy] = "alice"
            it[TodosTable.createdAt] = Instant.now()
        }
        id
    }

    private fun sentAt(id: UUID): Instant? = transaction {
        TodosTable.selectAll().where { TodosTable.id eq id }.single()[TodosTable.reminderSentAt]
    }

    private fun service(client: TelegramClient, enabled: Boolean = true, quietStart: LocalTime? = null, quietEnd: LocalTime? = null) =
        ReminderService(client, enabled = { enabled }, quietStart = { quietStart }, quietEnd = { quietEnd }, zone = ZoneId.of("UTC"))

    private val now = LocalDateTime.parse("2026-07-01T14:30")

    @Test
    fun `fires once for a due timed todo and stamps it`() = runBlocking {
        val client = FakeTelegramClient()
        val id = insertTodo("Zahnarzt", LocalDate.parse("2026-07-01"), LocalTime.parse("14:30"))
        val svc = service(client)

        svc.runOnce(now)
        assertEquals(listOf("🔔 Erinnerung: Zahnarzt — fällig 14:30"), client.messages)
        assertNotNull(sentAt(id))

        // a second pass must not re-send (fire-once)
        svc.runOnce(now.plusMinutes(1))
        assertEquals(1, client.messages.size)
    }

    @Test
    fun `a date-only todo never fires`() = runBlocking {
        val client = FakeTelegramClient()
        val id = insertTodo("Irgendwas heute", LocalDate.parse("2026-07-01"), dueTime = null)
        service(client).runOnce(now)
        assertTrue(client.messages.isEmpty())
        assertNull(sentAt(id))
    }

    @Test
    fun `a DONE todo never fires`() = runBlocking {
        val client = FakeTelegramClient()
        insertTodo("Erledigt", LocalDate.parse("2026-07-01"), LocalTime.parse("14:30"), status = "DONE")
        service(client).runOnce(now)
        assertTrue(client.messages.isEmpty())
    }

    @Test
    fun `a future todo waits`() = runBlocking {
        val client = FakeTelegramClient()
        val id = insertTodo("Später", LocalDate.parse("2026-07-01"), LocalTime.parse("16:00"))
        service(client).runOnce(now)
        assertTrue(client.messages.isEmpty())
        assertNull(sentAt(id)) // not stamped — still pending
    }

    @Test
    fun `a stale overdue todo is retired without sending`() = runBlocking {
        val client = FakeTelegramClient()
        // fired at 14:30; now is >12h later → past the catch-up window
        val id = insertTodo("Verpasst", LocalDate.parse("2026-07-01"), LocalTime.parse("14:30"))
        service(client).runOnce(LocalDateTime.parse("2026-07-02T06:00"))
        assertTrue(client.messages.isEmpty())
        assertNotNull(sentAt(id)) // retired (stamped) so it won't be re-checked
        Unit
    }

    @Test
    fun `lead time fires the reminder early`() = runBlocking {
        val client = FakeTelegramClient()
        insertTodo("Vorlauf", LocalDate.parse("2026-07-01"), LocalTime.parse("15:00"), lead = 30)
        // 14:30 is exactly 30 min before 15:00 → fires now
        service(client).runOnce(now)
        assertEquals(1, client.messages.size)
    }

    @Test
    fun `disabled sends nothing`() = runBlocking {
        val client = FakeTelegramClient()
        val id = insertTodo("Aus", LocalDate.parse("2026-07-01"), LocalTime.parse("14:30"))
        service(client, enabled = false).runOnce(now)
        assertTrue(client.messages.isEmpty())
        assertNull(sentAt(id))
    }

    @Test
    fun `quiet hours hold the whole pass`() = runBlocking {
        val client = FakeTelegramClient()
        val id = insertTodo("Nachts", LocalDate.parse("2026-07-01"), LocalTime.parse("23:00"))
        // 23:30 is inside 22:00–07:00 quiet hours → nothing sent, nothing stamped (deferred)
        service(client, quietStart = LocalTime.of(22, 0), quietEnd = LocalTime.of(7, 0))
            .runOnce(LocalDateTime.parse("2026-07-01T23:30"))
        assertTrue(client.messages.isEmpty())
        assertNull(sentAt(id))

        // after the window ends (and within catch-up) it fires
        service(client, quietStart = LocalTime.of(22, 0), quietEnd = LocalTime.of(7, 0))
            .runOnce(LocalDateTime.parse("2026-07-02T07:01"))
        assertEquals(1, client.messages.size)
        assertNotNull(sentAt(id))
        Unit
    }
}
