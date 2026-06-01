package com.homebase

import com.homebase.db.TodosTable
import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestService
import com.homebase.digest.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigestTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 1)
    private val service = DigestService(zone)

    private class FakeTelegramClient : TelegramClient {
        val messages = mutableListOf<String>()
        override suspend fun sendMessage(text: String) { messages.add(text) }
    }

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:digest_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(TodosTable) }
    }

    private fun insertTodo(
        title: String,
        status: String,
        createdAt: Instant = today.atTime(9, 0).atZone(zone).toInstant(),
        doneAt: Instant? = null,
        dueDate: LocalDate? = null,
    ) = transaction {
        TodosTable.insert {
            it[TodosTable.id] = java.util.UUID.randomUUID()
            it[TodosTable.title] = title
            it[TodosTable.status] = status
            it[TodosTable.createdBy] = "alice"
            it[TodosTable.createdAt] = createdAt
            it[TodosTable.doneAt] = doneAt
            it[TodosTable.dueDate] = dueDate
        }
    }

    @Test
    fun `buildDigest groups done-today, new-inbox and due-tomorrow`() {
        val noonToday = today.atTime(12, 0).atZone(zone).toInstant()
        val yesterday = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant()

        insertTodo("Erledigt heute", status = "DONE", doneAt = noonToday)
        insertTodo("Erledigt gestern", status = "DONE", doneAt = yesterday)
        insertTodo("Neu heute", status = "INBOX", createdAt = noonToday)
        insertTodo("Alt", status = "INBOX", createdAt = yesterday)
        insertTodo("Morgen fällig", status = "PLANNED", dueDate = today.plusDays(1))
        insertTodo("Später fällig", status = "PLANNED", dueDate = today.plusDays(3))

        val content = service.buildDigest(today)

        assertEquals(listOf("Erledigt heute"), content.doneToday)
        assertEquals(listOf("Neu heute"), content.newInbox)
        assertEquals(listOf("Morgen fällig"), content.dueTomorrow)
        assertFalse(content.isEmpty)
    }

    @Test
    fun `buildDigest on empty data is empty`() {
        val content = service.buildDigest(today)
        assertTrue(content.isEmpty)
    }

    @Test
    fun `render lists items under each section`() {
        insertTodo("Erledigt heute", status = "DONE", doneAt = today.atTime(12, 0).atZone(zone).toInstant())
        insertTodo("Morgen fällig", status = "PLANNED", dueDate = today.plusDays(1))

        val text = service.render(service.buildDigest(today))

        assertContains(text, "2026-06-01")
        assertContains(text, "✅ Heute erledigt")
        assertContains(text, "• Erledigt heute")
        assertContains(text, "📥 Neu in der Inbox")
        assertContains(text, "— keine —") // no new inbox items
        assertContains(text, "📅 Morgen fällig")
        assertContains(text, "• Morgen fällig")
    }

    @Test
    fun `runDigest sends rendered message when content is present`() = runBlocking {
        insertTodo("Erledigt heute", status = "DONE", doneAt = today.atTime(12, 0).atZone(zone).toInstant())
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler(LocalTime.of(20, 0), service, client, CoroutineScope(EmptyCoroutineContext), zone)

        scheduler.runDigest(today)

        assertEquals(1, client.messages.size)
        assertContains(client.messages.first(), "Erledigt heute")
    }

    @Test
    fun `runDigest skips sending when digest is empty`() = runBlocking {
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler(LocalTime.of(20, 0), service, client, CoroutineScope(EmptyCoroutineContext), zone)

        scheduler.runDigest(today)

        assertTrue(client.messages.isEmpty())
    }

    @Test
    fun `millisUntilNextRun targets today when digest time is still ahead`() {
        val scheduler = DigestScheduler(LocalTime.of(20, 0), service, FakeTelegramClient(), CoroutineScope(EmptyCoroutineContext), zone)
        val now = ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, zone)

        assertEquals(10 * 60 * 60 * 1000L, scheduler.millisUntilNextRun(now))
    }

    @Test
    fun `millisUntilNextRun rolls to tomorrow when digest time has passed`() {
        val scheduler = DigestScheduler(LocalTime.of(20, 0), service, FakeTelegramClient(), CoroutineScope(EmptyCoroutineContext), zone)
        val now = ZonedDateTime.of(2026, 6, 1, 21, 0, 0, 0, zone)

        assertEquals(23 * 60 * 60 * 1000L, scheduler.millisUntilNextRun(now))
    }
}
