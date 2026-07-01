package com.homebase

import com.homebase.db.AbsencesTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodosTable
import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestSection
import com.homebase.digest.MorningDigestService
import com.homebase.digest.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorningDigestTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 1)
    private val service = MorningDigestService()

    private class FakeTelegramClient : TelegramClient {
        val messages = mutableListOf<String>()
        override suspend fun sendMessage(text: String) { messages.add(text) }
    }

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:morning_digest_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(TodosTable, TodoListsTable, AbsencesTable, KitaClosuresTable) }
    }

    private fun insertTodo(title: String, status: String, dueDate: LocalDate? = null, listId: UUID? = null) = transaction {
        TodosTable.insert {
            it[TodosTable.id] = UUID.randomUUID()
            it[TodosTable.title] = title
            it[TodosTable.status] = status
            it[TodosTable.createdBy] = "alice"
            it[TodosTable.createdAt] = today.atTime(9, 0).atZone(zone).toInstant()
            it[TodosTable.dueDate] = dueDate
            it[TodosTable.listId] = listId
        }
    }

    /** Creates a todo list of the given visibility and returns its id. */
    private fun insertList(visibility: String): UUID = transaction {
        val id = UUID.randomUUID()
        TodoListsTable.insert {
            it[TodoListsTable.id] = id
            it[TodoListsTable.name] = "Liste $visibility"
            it[TodoListsTable.visibility] = visibility
            it[TodoListsTable.createdBy] = "alice"
            it[TodoListsTable.createdAt] = today.atTime(0, 0).atZone(zone).toInstant()
        }
        id
    }

    private fun insertAbsence(userId: String, type: String, date: LocalDate = today, half: String? = null) = transaction {
        AbsencesTable.insert {
            it[AbsencesTable.id] = UUID.randomUUID()
            it[AbsencesTable.userId] = userId
            it[AbsencesTable.date] = date
            it[AbsencesTable.type] = type
            it[AbsencesTable.half] = half
        }
    }

    private fun insertKitaClosure(label: String, date: LocalDate = today) = transaction {
        KitaClosuresTable.insert {
            it[KitaClosuresTable.id] = UUID.randomUUID()
            it[KitaClosuresTable.date] = date
            it[KitaClosuresTable.label] = label
        }
    }

    @Test
    fun `buildDigest groups due-today, overdue, undated inbox, absences and kita closures`() {
        insertTodo("Heute fällig", status = "PLANNED", dueDate = today)
        insertTodo("Heute erledigt", status = "DONE", dueDate = today) // done → not "due today"
        insertTodo("Überfällig", status = "PLANNED", dueDate = today.minusDays(2))
        insertTodo("Erledigt überfällig", status = "DONE", dueDate = today.minusDays(2)) // done → not overdue
        insertTodo("Zu triagieren", status = "INBOX") // no due date → inbox
        insertTodo("Inbox mit Datum", status = "INBOX", dueDate = today) // dated → shows as due-today, not inbox
        insertTodo("Morgen fällig", status = "PLANNED", dueDate = today.plusDays(1)) // future → nowhere today

        insertAbsence("alice", type = "URLAUB")
        insertAbsence("bob", type = "KRANK", half = "vm")
        insertAbsence("alice", type = "KIND_KRANK", date = today.plusDays(1)) // other day → excluded

        insertKitaClosure("Brückentag")
        insertKitaClosure("Sommerpause", date = today.minusDays(3)) // other day → excluded

        val content = service.buildDigest(today)

        assertEquals(listOf("Heute fällig", "Inbox mit Datum").sorted(), content.dueToday.sorted())
        assertEquals(listOf("Überfällig"), content.overdue)
        assertEquals(listOf("Zu triagieren"), content.inbox)
        assertEquals(listOf("alice — Urlaub", "bob — Krank (vormittags)"), content.absent)
        assertEquals(listOf("Brückentag"), content.kitaClosed)
        assertFalse(content.isEmpty)
    }

    @Test
    fun `buildDigest omits todos in a private list (they'd leak to the shared chat)`() {
        val privateList = insertList("PRIVATE")
        val sharedList = insertList("SHARED")

        // one per section in a PRIVATE list — none may surface
        insertTodo("Geheim heute", status = "PLANNED", dueDate = today, listId = privateList)
        insertTodo("Geheim überfällig", status = "PLANNED", dueDate = today.minusDays(2), listId = privateList)
        insertTodo("Geheim inbox", status = "INBOX", listId = privateList)
        // shared-list + list-less counterparts must still appear
        insertTodo("Offen heute", status = "PLANNED", dueDate = today, listId = sharedList)
        insertTodo("Offen inbox", status = "INBOX") // list-less

        val content = service.buildDigest(today)

        assertEquals(listOf("Offen heute"), content.dueToday)
        assertTrue(content.overdue.isEmpty()) // the only overdue todo was private
        assertEquals(listOf("Offen inbox"), content.inbox)
    }

    @Test
    fun `buildDigest on empty data is empty`() {
        assertTrue(service.buildDigest(today).isEmpty)
    }

    @Test
    fun `render lists present sections and omits empty ones`() {
        insertTodo("Heute fällig", status = "PLANNED", dueDate = today)
        insertKitaClosure("Brückentag")

        val text = service.render(service.buildDigest(today))!!

        assertContains(text, "Guten Morgen")
        assertContains(text, "2026-06-01")
        assertContains(text, "📅 Heute fällig")
        assertContains(text, "• Heute fällig")
        assertContains(text, "🚸 Kita geschlossen")
        assertContains(text, "• Brückentag")
        // empty sections are omitted entirely (no "— keine —" placeholder like the evening recap)
        assertFalse(text.contains("⚠️ Überfällig"))
        assertFalse(text.contains("📥 Inbox"))
        assertFalse(text.contains("🏖️ Heute abwesend"))
        assertFalse(text.contains("— keine —"))
    }

    @Test
    fun `render omits deselected sections and skips when only deselected ones have content (#182)`() {
        insertTodo("Heute fällig", status = "PLANNED", dueDate = today)
        insertTodo("Überfällig", status = "PLANNED", dueDate = today.minusDays(2))
        val content = service.buildDigest(today)

        // Only the overdue section selected → due-today is omitted.
        val onlyOverdue = service.render(content, setOf(DigestSection.MORNING_OVERDUE))!!
        assertContains(onlyOverdue, "⚠️ Überfällig")
        assertContains(onlyOverdue, "• Überfällig")
        assertFalse(onlyOverdue.contains("📅 Heute fällig"))

        // A digest whose only selected section is empty (here: inbox, which has no items) renders
        // nothing → null → the scheduler skips it.
        assertEquals(null, service.render(content, setOf(DigestSection.MORNING_INBOX)))
    }

    @Test
    fun `render caps an unbounded section and summarizes the rest (#167)`() {
        // 25 overdue todos > MAX_SECTION_ITEMS (20) → 20 bullets + a "… und 5 weitere" line.
        repeat(25) { i -> insertTodo("Alt-%02d".format(i), status = "PLANNED", dueDate = today.minusDays(3)) }
        val text = service.render(service.buildDigest(today))!!

        assertEquals(20, text.lines().count { it.startsWith("• Alt-") })
        assertContains(text, "… und 5 weitere")
    }

    @Test
    fun `service honors the section selection provider so a deselected section never sends (#182)`() = runBlocking {
        insertTodo("Heute fällig", status = "PLANNED", dueDate = today)
        // Provider deselects everything but the (empty) inbox → nothing to send.
        val client = FakeTelegramClient()
        val gated = MorningDigestService(sections = { setOf(DigestSection.MORNING_INBOX) })
        DigestScheduler({ LocalTime.of(7, 0) }, gated, client, CoroutineScope(EmptyCoroutineContext), zone).runDigest(today)
        assertTrue(client.messages.isEmpty())
    }

    @Test
    fun `runDigest sends the briefing when content is present`() = runBlocking {
        insertTodo("Heute fällig", status = "PLANNED", dueDate = today)
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler({ LocalTime.of(7, 0) }, service, client, CoroutineScope(EmptyCoroutineContext), zone)

        scheduler.runDigest(today)

        assertEquals(1, client.messages.size)
        assertContains(client.messages.first(), "Heute fällig")
    }

    @Test
    fun `runDigest skips sending when the briefing is empty`() = runBlocking {
        val client = FakeTelegramClient()
        val scheduler = DigestScheduler({ LocalTime.of(7, 0) }, service, client, CoroutineScope(EmptyCoroutineContext), zone)

        scheduler.runDigest(today)

        assertTrue(client.messages.isEmpty())
    }
}
