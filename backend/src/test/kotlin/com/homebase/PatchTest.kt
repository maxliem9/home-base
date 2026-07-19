package com.homebase

import com.homebase.service.Patch
import com.homebase.service.asPatch
import com.homebase.service.resolve
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the tri-state PATCH carrier (#265/#556): the null/blank/value → keep/clear/set convention that
 * every PUT merge used to hand-roll per field.
 */
class PatchTest {

    @Test
    fun `asPatch classifies the three wire states`() {
        assertEquals(Patch.Keep, (null as String?).asPatch())          // field absent
        assertEquals(Patch.Clear, "".asPatch())                         // sent blank
        assertEquals(Patch.Clear, "   ".asPatch())                      // whitespace counts as blank
        assertEquals(Patch.Set("hi"), "hi".asPatch())                   // sent with a value
    }

    @Test
    fun `resolve applies keep clear and set against the stored value`() {
        assertEquals("stored", Patch.Keep.resolve("stored"))            // keep the current value
        assertEquals(null, Patch.Keep.resolve(null))                    // keep null when nothing stored
        assertEquals(null, Patch.Clear.resolve("stored"))              // clear wipes the stored value
        assertEquals("new", Patch.Set("new").resolve("stored"))         // set overrides
    }

    @Test
    fun `asPatch then resolve reproduces the old per-field merge exactly`() {
        // old: if (req.x != null) req.x.ifBlank { null } else existing[x]
        fun oldMerge(req: String?, existing: String?): String? =
            if (req != null) req.ifBlank { null } else existing
        val reqs = listOf(null, "", "  ", "value")
        val stored = listOf(null, "old")
        for (r in reqs) for (s in stored) {
            assertEquals(oldMerge(r, s), r.asPatch().resolve(s), "req=$r stored=$s")
        }
    }
}
