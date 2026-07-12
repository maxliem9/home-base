package com.homebase

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.CustomHolidaysTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.UsersTable
import com.homebase.model.UpdateAbsSettingsRequest
import com.homebase.model.UpdateCustomHolidayRequest
import com.homebase.service.AbsenceService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AbsenceService] (issue #566): the unknown-user gate, absence upsert, idempotent
 * kita/holiday creation, the move-onto-taken-date 409 and settings inheritance — without an HTTP
 * layer. The full HTTP contract stays covered by AbsenceRouteTest.
 */
class AbsenceServiceTest {

    private val service = AbsenceService()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:absenceservice_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(UsersTable, AbsencesTable, PartTimeRulesTable, KitaClosuresTable, CustomHolidaysTable, AbsSettingsTable)
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

    @Test
    fun `setAbsence rejects an unknown user`() = runBlocking {
        assertNull(service.setAbsence("carol", LocalDate.parse("2026-07-01"), "URLAUB", null))
        val ok = service.setAbsence("alice", LocalDate.parse("2026-07-01"), "URLAUB", null)
        assertEquals("URLAUB", ok?.type)
    }

    @Test
    fun `setAbsence upserts - a second write on the same day replaces the first`() = runBlocking {
        val date = LocalDate.parse("2026-07-01")
        service.setAbsence("alice", date, "URLAUB", null)
        service.setAbsence("alice", date, "KRANK", "vm")
        val snap = service.snapshot()
        val forDay = snap.absences.filter { it.userId == "alice" && it.date == date.toString() }
        assertEquals(1, forDay.size)
        assertEquals("KRANK", forDay.single().type)
        assertEquals("vm", forDay.single().half)
    }

    @Test
    fun `kita upsert is idempotent on the date`() = runBlocking {
        val date = LocalDate.parse("2026-12-24")
        val first = service.upsertKita(date, "Weihnachten")
        assertTrue(first.created)
        val second = service.upsertKita(date, "egal")
        assertFalse(second.created)
        assertEquals(first.dto.id, second.dto.id)
    }

    @Test
    fun `moving a holiday onto a taken date is a conflict`() = runBlocking {
        service.upsertHoliday(5, 1, half = false, label = "Tag der Arbeit")
        val second = service.upsertHoliday(12, 6, half = false, label = "Nikolaus")

        val r = service.updateHoliday(UUID.fromString(second.dto.id), UpdateCustomHolidayRequest(month = 5, day = 1))
        assertEquals(AbsenceService.HolidayUpdateResult.Conflict, r)
    }

    @Test
    fun `updating a holiday to an invalid date is rejected`() = runBlocking {
        val h = service.upsertHoliday(6, 15, half = false, label = "X")
        val r = service.updateHoliday(UUID.fromString(h.dto.id), UpdateCustomHolidayRequest(month = 2, day = 30))
        assertEquals(AbsenceService.HolidayUpdateResult.InvalidDate, r)
    }

    @Test
    fun `settings for a new year inherit the state from the nearest year but not the carryover`() = runBlocking {
        service.upsertSettings("alice", 2025, UpdateAbsSettingsRequest(state = "BY", allowance = 28.0, carryover = 5.0), expires = null)
        // a fresh 2026 row inherits BY + allowance, but carryover resets to 0
        val next = service.upsertSettings("alice", 2026, UpdateAbsSettingsRequest(), expires = null)
        assertEquals("BY", next?.state)
        assertEquals(28.0, next?.allowance)
        assertEquals(0.0, next?.carryover)
    }
}
