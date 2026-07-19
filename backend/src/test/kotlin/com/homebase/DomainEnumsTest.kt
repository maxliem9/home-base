package com.homebase

import com.homebase.model.ListVisibility
import com.homebase.model.RecurrenceFreq
import com.homebase.model.TodoPriority
import com.homebase.model.TodoStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the typed domain enums (#556) that replaced the ad-hoc `VALID_*`/`FREQUENCIES` string sets:
 * the enum name is the wire/DB string, and `parse` returns null for anything unknown so the service
 * can raise the specific `INVALID_*` code.
 */
class DomainEnumsTest {

    @Test
    fun `parse accepts the exact wire strings`() {
        assertEquals(TodoStatus.INBOX, TodoStatus.parse("INBOX"))
        assertEquals(TodoStatus.DONE, TodoStatus.parse("DONE"))
        assertEquals(TodoPriority.HIGH, TodoPriority.parse("HIGH"))
        assertEquals(ListVisibility.SHARED, ListVisibility.parse("SHARED"))
        assertEquals(ListVisibility.PRIVATE, ListVisibility.parse("PRIVATE"))
        assertEquals(RecurrenceFreq.WEEKLY, RecurrenceFreq.parse("WEEKLY"))
    }

    @Test
    fun `parse rejects unknown values, null, and is case-sensitive`() {
        assertNull(TodoStatus.parse("inbox"))       // wire is upper-case
        assertNull(TodoStatus.parse("ARCHIVED"))
        assertNull(TodoPriority.parse(null))
        assertNull(ListVisibility.parse(""))
        // "NONE" is the recurrence *clear* sentinel, deliberately NOT a frequency.
        assertNull(RecurrenceFreq.parse("NONE"))
    }

    @Test
    fun `enum names are the wire strings (byte-identical serialization)`() {
        assertEquals("INBOX,PLANNED,DONE", TodoStatus.entries.joinToString(",") { it.name })
        assertEquals("LOW,MEDIUM,HIGH", TodoPriority.entries.joinToString(",") { it.name })
        assertEquals("SHARED,PRIVATE", ListVisibility.entries.joinToString(",") { it.name })
        assertEquals("DAILY,WEEKLY,MONTHLY", RecurrenceFreq.entries.joinToString(",") { it.name })
    }
}
