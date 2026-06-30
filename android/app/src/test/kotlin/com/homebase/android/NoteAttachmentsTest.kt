package com.homebase.android

import com.homebase.android.ui.notes.NoteAttachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for the note file-attachment chip (#437): byte-for-byte parity with the web
 * `formatBytes`, plus the is-image classification.
 */
class NoteAttachmentsTest {

    @Test
    fun `formatFileSize renders bytes, KB and MB like the web formatBytes`() {
        assertEquals("0 B", NoteAttachments.formatFileSize(0))
        assertEquals("512 B", NoteAttachments.formatFileSize(512))
        assertEquals("1023 B", NoteAttachments.formatFileSize(1023))
        // exactly 1 KiB → rounded KB
        assertEquals("1 KB", NoteAttachments.formatFileSize(1024))
        assertEquals("2 KB", NoteAttachments.formatFileSize(1536)) // 1.5 KiB rounds to 2
        assertEquals("1000 KB", NoteAttachments.formatFileSize(1024L * 1000))
        // >= 1 MiB → one decimal, US grouping (always a dot)
        assertEquals("1.0 MB", NoteAttachments.formatFileSize(1024L * 1024))
        assertEquals("2.5 MB", NoteAttachments.formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `isImageContentType only matches image MIME types`() {
        assertTrue(NoteAttachments.isImageContentType("image/png"))
        assertTrue(NoteAttachments.isImageContentType("IMAGE/JPEG"))
        assertTrue(NoteAttachments.isImageContentType("  image/webp  "))
        assertFalse(NoteAttachments.isImageContentType("application/pdf"))
        assertFalse(NoteAttachments.isImageContentType("text/plain"))
        assertFalse(NoteAttachments.isImageContentType(null))
        assertFalse(NoteAttachments.isImageContentType(""))
    }

    @Test
    fun `accept MIME types cover the backend attachment whitelist`() {
        // The picker filter must include the document types the backend accepts (#431). Spot-check a
        // representative slice; the array is the source of truth for the picker.
        val types = NoteAttachments.ACCEPT_MIME_TYPES.toSet()
        assertTrue("application/pdf" in types)
        assertTrue("text/csv" in types)
        assertTrue("application/zip" in types)
        assertTrue("application/vnd.openxmlformats-officedocument.wordprocessingml.document" in types)
        // images are NOT offered here — they go through the photo picker
        assertFalse(types.any { it.startsWith("image/") })
    }
}
