package com.homebase

import com.homebase.routes.NoteImageConfig
import com.homebase.routes.sweepStaleImageUploads
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteImageSweepTest {

    @Test
    fun `sweep removes leftover upload temp files but keeps stored images`() {
        val dir = Files.createTempDirectory("homebase-sweep-test")
        val stale1 = Files.createFile(dir.resolve("upload-123.tmp"))
        val stale2 = Files.createFile(dir.resolve("upload-456.tmp"))
        val stored = Files.createFile(dir.resolve("11111111-1111-1111-1111-111111111111.png"))

        val swept = sweepStaleImageUploads(NoteImageConfig(dir, maxBytes = 1024))

        assertEquals(2, swept)
        assertFalse(Files.exists(stale1))
        assertFalse(Files.exists(stale2))
        assertTrue(Files.exists(stored), "a finalized image must survive the sweep")
    }

    @Test
    fun `sweep on a missing dir is a no-op`() {
        val missing = Files.createTempDirectory("homebase-sweep-test").resolve("nope")
        assertEquals(0, sweepStaleImageUploads(NoteImageConfig(missing, maxBytes = 1024)))
    }
}
