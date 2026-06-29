package com.homebase.android

import com.homebase.android.ui.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/** Pure tests for the due-time helpers added with #429 (Phase 1). */
class FormatDueTimeTest {

    @Test
    fun `parseLocalTime accepts HH-mm and HH-mm-ss, rejects junk`() {
        assertEquals(LocalTime.of(14, 30), Format.parseLocalTime("14:30"))
        assertEquals(LocalTime.of(8, 15), Format.parseLocalTime("08:15:00"))
        assertNull(Format.parseLocalTime(null))
        assertNull(Format.parseLocalTime(""))
        assertNull(Format.parseLocalTime("nope"))
        assertNull(Format.parseLocalTime("25:99"))
    }

    @Test
    fun `dueTimeShort normalizes to HH-mm or null`() {
        assertEquals("14:30", Format.dueTimeShort("14:30"))
        assertEquals("08:15", Format.dueTimeShort("08:15:00"))
        assertNull(Format.dueTimeShort(null))
        assertNull(Format.dueTimeShort(""))
        assertNull(Format.dueTimeShort("bad"))
    }
}
