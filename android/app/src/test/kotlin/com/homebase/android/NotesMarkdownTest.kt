package com.homebase.android

import com.homebase.android.ui.notes.MdBlock
import com.homebase.android.ui.notes.isSafeLinkUrl
import com.homebase.android.ui.notes.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Compose-free notes markdown logic (#145): image-ref block splitting and the
 * link/image URL allowlist. Mirrors the web's markdown.test.ts so the two renderers stay
 * in sync on the security-sensitive parts.
 */
class NotesMarkdownTest {

    @Test
    fun `an image on its own line becomes an Image block`() {
        val blocks = parseMarkdown("![Diagramm](image:abc123)")
        assertEquals(1, blocks.size)
        val img = blocks[0] as MdBlock.Image
        assertEquals("Diagramm", img.alt)
        assertEquals("image:abc123", img.src)
    }

    @Test
    fun `an inline image splits the surrounding paragraph`() {
        val blocks = parseMarkdown("vor ![x](image:1) nach")
        assertEquals(3, blocks.size)
        assertEquals("vor", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("image:1", (blocks[1] as MdBlock.Image).src)
        assertEquals("nach", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun `an external image src is kept as-is`() {
        val img = parseMarkdown("![c](https://example.com/c.png)").single() as MdBlock.Image
        assertEquals("https://example.com/c.png", img.src)
    }

    @Test
    fun `plain text without images stays a single paragraph`() {
        val blocks = parseMarkdown("nur text")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `headings, lists and quotes still parse`() {
        val blocks = parseMarkdown("## Titel\n- a\n- b\n> zitat")
        assertTrue(blocks[0] is MdBlock.Heading2)
        assertEquals(listOf("a", "b"), (blocks[1] as MdBlock.BulletList).items)
        assertTrue(blocks[2] is MdBlock.Quote)
    }

    @Test
    fun `safe link schemes are allowed (case-insensitive)`() {
        assertTrue(isSafeLinkUrl("https://example.com"))
        assertTrue(isSafeLinkUrl("http://example.com"))
        assertTrue(isSafeLinkUrl("mailto:a@b.de"))
        assertTrue(isSafeLinkUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `unsafe or non-openable schemes are rejected`() {
        assertFalse(isSafeLinkUrl("javascript:alert(1)"))
        assertFalse(isSafeLinkUrl("JavaScript:alert(1)"))
        assertFalse(isSafeLinkUrl("data:text/html,x"))
        assertFalse(isSafeLinkUrl("//evil.com")) // protocol-relative
        assertFalse(isSafeLinkUrl("/relative/path")) // not openable on Android
        assertFalse(isSafeLinkUrl("#anchor"))
    }
}
