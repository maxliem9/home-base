package com.homebase

import com.homebase.db.AbsencesTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodosTable
import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestSection
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
        transaction { SchemaUtils.create(TodosTable, TodoListsTable, AbsencesTable, KitaClosuresTable) }
    }

    /** Creates a todo list of the given visibility and returns its id. */
    private fun insertList(visibility: String): java.util.UUID = transaction {
        val id = java.util.UUID.randomUUID()
        TodoListsTable.insert {
            it[TodoListsTable.id] = id
            it[TodoListsTable.name] = "Liste $visibility"
            it[TodoListsTable.visibility] = visibility
            it[TodoListsTable.createdBy] = "alice"
            it[TodoListsTable.createdAt] = Instant.now()
        }
        id
    }

    private fun insertAbsence(userId: String, type: String, date: LocalDate, half: String? = null) = transaction {
        AbsencesTable.insert {
            it[AbsencesTable.id] = java.util.UUID.randomUUID()
            it[AbsencesTable.userId] = userId
            it[AbsencesTable.date] = date
            it[AbsencesTable.type] = type
            it[AbsencesTable.half] = half
        }
    }

    private fun insertKitaClosure(label: String, date: LocalDate) = transaction {
        KitaClosuresTable.insert {
            it[KitaClosuresTable.id] = java.util.UUID.randomUUID()
            it[KitaClosuresTable.date] = date
            it[KitaClosuresTable.label] = label
        }
    }

    private fun insertTodo(
        title: String,
        status: String,
        createdAt: Instant = today.atTime(9, 0).atZone(zone).toInstant(),
        doneAt: Instant? = null,
        dueDate: LocalDate? = null,
        listId: java.util.UUID? = null,
    ) = transaction {
        TodosTable.insert {
            it[TodosTable.id] = java.util.UUID.randomUUID()
            it[TodosTable.title] = title
            it[TodosTable.status] = status
            it[TodosTable.createdBy] = "alice"
            it[TodosTable.createdAt] = createdAt
            it[TodosTable.doneAt] = doneAt
            it[TodosTable.dueDate] = dueDate
            it[TodosTable.listId] = listId
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
    fun `buildDigest omits todos in a private list (they'd leak to the shared chat)`() {
        val noonToday = today.atTime(12, 0).atZone(zone).toInstant()
        val privateList = insertList("PRIVATE")
        val sharedList = insertList("SHARED")

        // one of each section in a PRIVATE list — none may surface
        insertTodo("Geheim erledigt", status = "DONE", doneAt = noonToday, listId = privateList)
        insertTodo("Geheim neu", status = "INBOX", createdAt = noonToday, listId = privateList)
        insertTodo("Geheim morgen", status = "PLANNED", dueDate = today.plusDays(1), listId = privateList)
        // shared-list + list-less counterparts must still appear
        insertTodo("Offen erledigt", status = "DONE", doneAt = noonToday, listId = sharedList)
        insertTodo("Offen neu", status = "INBOX", createdAt = noonToday) // list-less

        val content = service.buildDigest(today)

        assertEquals(listOf("Offen erledigt"), content.doneToday)
        assertEquals(listOf("Offen neu"), content.newInbox)
        assertTrue(content.dueTomorrow.isEmpty()) // the only "morgen fällig" was private
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
    fun `buildDigest and render include tomorrow's absence and kita preview (#182)`() {
        val tomorrow = today.plusDays(1)
        insertTodo("Morgen fällig", status = "PLANNED", dueDate = tomorrow)
        insertAbsence("alice", type = "URLAUB", date = tomorrow)
        insertAbsence("bob", type = "KIND_KRANK", date = tomorrow, half = "nm")
        insertAbsence("alice", type = "KRANK", date = today) // today, not tomorrow → excluded
        insertKitaClosure("Brückentag", date = tomorrow)
        insertKitaClosure("Heute zu", date = today) // today → excluded

        val content = service.buildDigest(today)
        assertEquals(listOf("alice — Urlaub", "bob — Kind krank (nachmittags)"), content.absentTomorrow)
        assertEquals(listOf("Brückentag"), content.kitaClosedTomorrow)

        val text = service.render(content)
        assertContains(text, "🏖️ Morgen abwesend")
        assertContains(text, "• alice — Urlaub")
        assertContains(text, "🚸 Kita morgen geschlossen")
        assertContains(text, "• Brückentag")
    }

    @Test
    fun `render omits a deselected section and the empty tomorrow-preview sections (#182)`() {
        insertTodo("Erledigt heute", status = "DONE", doneAt = today.atTime(12, 0).atZone(zone).toInstant())
        val content = service.buildDigest(today)

        // Deselect the done-today section → its heading is gone even though it has an item.
        val text = service.render(content, DigestSection.evening.toSet() - DigestSection.EVENING_DONE_TODAY)
        assertFalse(text.contains("✅ Heute erledigt"))
        // Core sections still selected keep their "— keine —" placeholder…
        assertContains(text, "📥 Neu in der Inbox")
        assertContains(text, "— keine —")
        // …but the empty tomorrow-preview sections are omitted (extra context, not a checklist).
        assertFalse(text.contains("🏖️ Morgen abwesend"))
        assertFalse(text.contains("🚸 Kita morgen geschlossen"))
    }

    @Test
    fun `render caps an unbounded section and summarizes the rest (#167)`() {
        // 25 due-tomorrow todos > MAX_SECTION_ITEMS (20) → 20 bullets + "… und 5 weitere".
        repeat(25) { i -> insertTodo("Morgen-%02d".format(i), status = "PLANNED", dueDate = today.plusDays(1)) }
        val text = service.render(service.buildDigest(today))

        assertEquals(20, text.lines().count { it.startsWith("• Morgen-") })
        assertContains(text, "… und 5 weitere")
    }

    @Test
    fun `runDigest skips an evening with content only in deselected sections (#182)`() = runBlocking {
        insertTodo("Erledigt heute", status = "DONE", doneAt = today.atTime(12, 0).atZone(zone).toInstant())
        val client = FakeTelegramClient()
        // Only the (empty) due-tomorrow section is selected → nothing to send.
        val gated = DigestService(zone, sections = { setOf(DigestSection.EVENING_DUE_TOMORROW) })
        DigestScheduler({ LocalTime.of(20, 0) }, gated, client, CoroutineScope(EmptyCoroutineContext), zone).runDigest(today)
        assertTrue(client.messages.isEmpty())
    }

    @Test
    fun `runDigest skips entirely when the digest is disabled (#182)`() = runBlocking {
        insertTodo("Erledigt heute", status = "DONE", doneAt = today.atTime(12, 0).atZone(zone).toInstant())
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler(
            { LocalTime.of(20, 0) }, service, client, CoroutineScope(EmptyCoroutineContext), zone,
            enabled = { false },
        )

        scheduler.runDigest(today)

        assertTrue(client.messages.isEmpty())
    }

    @Test
    fun `runDigest sends rendered message when content is present`() = runBlocking {
        insertTodo("Erledigt heute", status = "DONE", doneAt = today.atTime(12, 0).atZone(zone).toInstant())
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler({ LocalTime.of(20, 0) }, service, client, CoroutineScope(EmptyCoroutineContext), zone)

        scheduler.runDigest(today)

        assertEquals(1, client.messages.size)
        assertContains(client.messages.first(), "Erledigt heute")
    }

    @Test
    fun `runDigest skips sending when digest is empty`() = runBlocking {
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler({ LocalTime.of(20, 0) }, service, client, CoroutineScope(EmptyCoroutineContext), zone)

        scheduler.runDigest(today)

        assertTrue(client.messages.isEmpty())
    }

    @Test
    fun `millisUntilNextRun targets today when digest time is still ahead`() {
        val scheduler = DigestScheduler({ LocalTime.of(20, 0) }, service, FakeTelegramClient(), CoroutineScope(EmptyCoroutineContext), zone)
        val now = ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, zone)

        assertEquals(10 * 60 * 60 * 1000L, scheduler.millisUntilNextRun(now))
    }

    @Test
    fun `millisUntilNextRun rolls to tomorrow when digest time has passed`() {
        val scheduler = DigestScheduler({ LocalTime.of(20, 0) }, service, FakeTelegramClient(), CoroutineScope(EmptyCoroutineContext), zone)
        val now = ZonedDateTime.of(2026, 6, 1, 21, 0, 0, 0, zone)

        assertEquals(23 * 60 * 60 * 1000L, scheduler.millisUntilNextRun(now))
    }

    @Test
    fun `millisUntilNextRun re-reads the provided time each call so an in-app edit is picked up`() {
        var time = LocalTime.of(20, 0)
        val scheduler = DigestScheduler({ time }, service, FakeTelegramClient(), CoroutineScope(EmptyCoroutineContext), zone)
        val now = ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, zone)
        assertEquals(10 * 60 * 60 * 1000L, scheduler.millisUntilNextRun(now))

        // Changing the provider's value (as an in-app edit to app_settings would) is
        // reflected on the next computation — the scheduler isn't pinned to a start-time value.
        time = LocalTime.of(12, 0)
        assertEquals(2 * 60 * 60 * 1000L, scheduler.millisUntilNextRun(now))
    }
}
