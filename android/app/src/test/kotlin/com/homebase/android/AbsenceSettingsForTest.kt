package com.homebase.android

import com.homebase.android.data.model.AbsSettingsDto
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.ui.abwesenheit.buildContext
import com.homebase.android.ui.abwesenheit.settingsFor
import com.homebase.android.ui.abwesenheit.summarize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the year-aware absence settings resolution (#144). Mirrors the web
 * `core.test.ts`: settings are stored per (user, year); a year without its own row inherits
 * the stable fields from the nearest year while resetting the per-year carryover.
 */
class AbsenceSettingsForTest {

    private fun s(
        year: Int,
        userId: String = "alice",
        state: String = "BE",
        allowance: Double = 30.0,
        carryover: Double = 0.0,
        kindKrankCap: Int = 15,
    ) = AbsSettingsDto(userId, year, state, allowance, carryover, "$year-03-31", kindKrankCap)

    @Test
    fun `returns the exact row when the year has its own settings`() {
        val all = listOf(s(2025, carryover = 5.0), s(2026, carryover = 2.0))
        assertEquals(2.0, settingsFor(all, "alice", 2026).carryover, 0.0)
        assertEquals(5.0, settingsFor(all, "alice", 2025).carryover, 0.0)
    }

    @Test
    fun `falls back to hard defaults when the user has no rows at all`() {
        val d = settingsFor(emptyList(), "alice", 2027)
        assertEquals("alice", d.userId)
        assertEquals(2027, d.year)
        assertEquals("BE", d.state)
        assertEquals(30.0, d.allowance, 0.0)
        assertEquals(0.0, d.carryover, 0.0)
        assertEquals(15, d.kindKrankCap)
    }

    @Test
    fun `inherits stable fields from the nearest earlier year but resets carryover`() {
        val all = listOf(s(2025, state = "BY", allowance = 28.0, kindKrankCap = 10, carryover = 5.0))
        val got = settingsFor(all, "alice", 2027)
        assertEquals("BY", got.state) // inherited
        assertEquals(28.0, got.allowance, 0.0) // inherited
        assertEquals(10, got.kindKrankCap) // inherited
        assertEquals(2027, got.year)
        assertEquals(0.0, got.carryover, 0.0) // per-year, not inherited
        assertEquals("2027-03-31", got.carryoverExpires) // reset to the queried year
    }

    @Test
    fun `inherits from the nearest later year when no earlier year exists`() {
        val all = listOf(s(2026, state = "HH", carryover = 4.0))
        val got = settingsFor(all, "alice", 2024)
        assertEquals("HH", got.state)
        assertEquals(2024, got.year)
        assertEquals(0.0, got.carryover, 0.0)
    }

    @Test
    fun `scopes inheritance to the requested user`() {
        val all = listOf(s(2025, userId = "alice", state = "BY"), s(2025, userId = "bob", state = "SN"))
        assertEquals("SN", settingsFor(all, "bob", 2026).state)
    }

    @Test
    fun `summarize reflects the per-year carryover in the remaining balance`() {
        val state = AbsenceStateDto(
            users = listOf("alice"),
            settings = listOf(s(2025, allowance = 30.0, carryover = 5.0), s(2026, allowance = 30.0, carryover = 2.0)),
        )
        val c2025 = buildContext(state, 2025, listOf("alice"))
        val c2026 = buildContext(state, 2026, listOf("alice"))
        // remaining = allowance + carryover - used(0)
        assertEquals(35.0, summarize(c2025, "alice", "2025-06-01").remaining, 0.0)
        assertEquals(32.0, summarize(c2026, "alice", "2026-06-01").remaining, 0.0)
    }
}
