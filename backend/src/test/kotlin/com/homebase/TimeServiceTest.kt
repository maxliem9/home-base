package com.homebase

import com.homebase.db.ProjectsTable
import com.homebase.db.TimeEntriesTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.db.UsersTable
import com.homebase.model.UpdateTimeEntryRequest
import com.homebase.model.UpsertWorkTargetRequest
import com.homebase.service.TimeService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TimeService] (issue #564): project CRUD, the start-stops-previous timer invariant,
 * entry split and the Wochensoll one-default-per-period rule (#59) — exercised without HTTP. The full
 * HTTP contract stays covered by TimeRouteTest / ForecastRouteTest.
 */
class TimeServiceTest {

    private val service = TimeService()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:timeservice_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(UsersTable, ProjectsTable, TimeEntriesTable, TimeWorkTargetsTable)
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

    private suspend fun newProject() = service.createProject("Arbeit", "#4F46E5", "alice")

    @Test
    fun `starting a second timer stops the first for the same user`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)

        val first = service.startTimer("alice", "alice", pid, null)
        assertTrue(first is TimeService.StartResult.Ok)
        assertNull(first.stopped)

        val second = service.startTimer("alice", "alice", pid, null)
        assertTrue(second is TimeService.StartResult.Ok)
        // the previously running entry is auto-stopped and returned
        assertEquals(first.started.id, second.stopped?.id)
        assertTrue(second.stopped?.stoppedAt != null)

        // exactly one running timer remains
        val running = service.runningAll()
        assertEquals(1, running.size)
        assertEquals(second.started.id, running.first().id)
    }

    @Test
    fun `starting on an archived project is a conflict`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)
        service.archiveProject(pid, true)

        val r = service.startTimer("alice", "alice", pid, null)
        assertTrue(r is TimeService.StartResult.Conflict)
        assertEquals("PROJECT_ARCHIVED", r.error.code)
    }

    @Test
    fun `starting for an unknown partner is UserNotFound`() = runBlocking {
        val project = newProject()
        val r = service.startTimer("alice", "carol", UUID.fromString(project.id), null)
        assertEquals(TimeService.StartResult.UserNotFound, r)
    }

    @Test
    fun `split divides a completed entry at the cut time`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)
        val start = Instant.parse("2026-07-01T08:00:00Z")
        val stop = Instant.parse("2026-07-01T12:00:00Z")
        val created = service.createEntry("alice", "alice", pid, start, stop, "Vormittag")
        assertTrue(created is TimeService.CreateEntryResult.Ok)
        val id = UUID.fromString(created.entry.id)

        val splitAt = Instant.parse("2026-07-01T10:00:00Z")
        val r = service.splitEntry(id, splitAt, breakMinutes = 30)
        assertTrue(r is TimeService.SplitResult.Ok)
        assertEquals(splitAt.toString(), r.response.first.stoppedAt)
        assertEquals("2026-07-01T10:30:00Z", r.response.second.startedAt)
        assertEquals(stop.toString(), r.response.second.stoppedAt)
    }

    @Test
    fun `split cuts a running timer and keeps part two running`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)
        val start = service.startTimer("alice", "alice", pid, null)
        assertTrue(start is TimeService.StartResult.Ok)
        val id = UUID.fromString(start.started.id)
        // backdate the running timer so a cut can lie in its past (it started "now")
        val startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(4 * 3600)
        service.updateEntry(id, UpdateTimeEntryRequest(), null, startedAt, null)

        // cut two hours in, break 30 min — the forgotten-lunch-break case
        val splitAt = startedAt.plusSeconds(2 * 3600)
        val r = service.splitEntry(id, splitAt, breakMinutes = 30)
        assertTrue(r is TimeService.SplitResult.Ok)
        assertEquals(splitAt.toString(), r.response.first.stoppedAt)
        assertEquals(splitAt.plusSeconds(30 * 60).toString(), r.response.second.startedAt)
        assertNull(r.response.second.stoppedAt) // part two is still running
        // and it is the only running timer of that user
        val running = service.runningAll().filter { it.userId == "alice" }
        assertEquals(listOf(r.response.second.id), running.map { it.id })
    }

    @Test
    fun `splitting a running timer rejects a cut or break in the future`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)
        val start = service.startTimer("alice", "alice", pid, null)
        assertTrue(start is TimeService.StartResult.Ok)
        val id = UUID.fromString(start.started.id)
        val startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(600)
        service.updateEntry(id, UpdateTimeEntryRequest(), null, startedAt, null)

        val future = service.splitEntry(id, Instant.now().plusSeconds(600), 0)
        assertTrue(future is TimeService.SplitResult.Invalid)
        assertEquals("INVALID_RANGE", future.error.code)

        // cut is fine, but the break would end after now
        val withBreak = service.splitEntry(id, startedAt.plusSeconds(60), breakMinutes = 60)
        assertTrue(withBreak is TimeService.SplitResult.Invalid)
        assertEquals("INVALID_RANGE", withBreak.error.code)
    }

    @Test
    fun `first configured hours make the project the default automatically`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)

        val r = service.upsertTarget("alice", pid, UpsertWorkTargetRequest(weeklyHours = 40.0), LocalDate.parse("1970-01-01"))
        assertTrue(r is TimeService.TargetResult.Ok)
        assertEquals(40.0, r.target.weeklyHours)
        assertTrue(r.target.isDefault) // auto-default (#59)
    }

    @Test
    fun `removing the last default while hours remain is rejected`() = runBlocking {
        val project = newProject()
        val pid = UUID.fromString(project.id)
        service.upsertTarget("alice", pid, UpsertWorkTargetRequest(weeklyHours = 40.0), LocalDate.parse("1970-01-01"))

        val r = service.upsertTarget("alice", pid, UpsertWorkTargetRequest(isDefault = false), LocalDate.parse("1970-01-01"))
        assertTrue(r is TimeService.TargetResult.Fault)
        assertEquals("DEFAULT_REQUIRED", r.error.code)
    }
}
