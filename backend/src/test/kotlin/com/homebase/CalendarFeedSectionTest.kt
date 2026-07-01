package com.homebase

import com.homebase.routes.CalendarFeedSection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the per-user iCal-feed category selection parser (#427). The null-vs-empty
 * distinction is the subtle bit: an unset pref means "all" (back-compat), while an explicitly
 * empty selection means "none" and must not spring back to all.
 */
class CalendarFeedSectionTest {

    @Test
    fun `a null selection means all categories (fresh-account default)`() {
        assertEquals(CalendarFeedSection.all.toSet(), CalendarFeedSection.parseSelection(null))
    }

    @Test
    fun `an empty string means no categories (explicit deselect-all)`() {
        assertEquals(emptySet(), CalendarFeedSection.parseSelection(""))
    }

    @Test
    fun `a CSV selects exactly the listed categories`() {
        assertEquals(
            setOf(CalendarFeedSection.TODOS, CalendarFeedSection.MEALS),
            CalendarFeedSection.parseSelection("todos,meals"),
        )
    }

    @Test
    fun `unknown or stale ids are ignored`() {
        assertEquals(
            setOf(CalendarFeedSection.ABSENCES),
            CalendarFeedSection.parseSelection("absences, gibberish , lunch"),
        )
    }
}
