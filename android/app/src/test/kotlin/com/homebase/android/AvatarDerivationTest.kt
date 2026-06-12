package com.homebase.android

import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the portable, roster-free avatar derivation (#155) and the
 * same-first-letter disambiguation (#89). Initial = first letter upper-cased,
 * hue = deterministic hash of the full username, displayName = capitalised
 * username — never the seeded "Max"/"Lea". Mirrors web's userMeta() tests in
 * web/src/ui/format.test.ts.
 */
class AvatarDerivationTest {

    @Test
    fun `initial is the first letter upper-cased for any username`() {
        assertEquals("M", Hb.userInitial("max"))
        assertEquals("C", Hb.userInitial("chen"))
        assertEquals("Z", Hb.userInitial("Zoe"))
    }

    @Test
    fun `blank or null username falls back to a placeholder initial`() {
        assertEquals("?", Hb.userInitial(null))
        assertEquals("?", Hb.userInitial(""))
        assertEquals("?", Hb.userInitial("   "))
    }

    @Test
    fun `hue is deterministic and case-insensitive within 0 until 360`() {
        val a = Hb.userHue("Bob")
        val b = Hb.userHue("bob")
        assertEquals(a, b, 0.0) // case-insensitive, stable
        assertTrue(a >= 0.0 && a < 360.0)
    }

    // #89: two members sharing a first letter get the same initial but must be
    // visually distinguishable — their hues differ because the *whole* username
    // is hashed.
    @Test
    fun `same-first-letter usernames share an initial but get different hues`() {
        assertEquals("M", Hb.userInitial("max"))
        assertEquals("M", Hb.userInitial("martina"))
        assertNotEquals(Hb.userHue("max"), Hb.userHue("martina"))
    }

    @Test
    fun `displayName capitalises the username and never hard-codes Max`() {
        assertEquals("Max", displayName("max"))
        assertEquals("Martina", displayName("martina"))
        assertEquals("Chen", displayName("chen"))
        // blank/unknown → neutral placeholder, not the seeded roster
        assertEquals("?", displayName(null))
        assertEquals("?", displayName("  "))
    }
}
