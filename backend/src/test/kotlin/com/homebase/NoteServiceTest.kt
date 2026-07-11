package com.homebase

import com.homebase.db.NoteAttachmentsTable
import com.homebase.db.NoteImagesTable
import com.homebase.db.NotesTable
import com.homebase.model.UpdateNoteRequest
import com.homebase.service.NoteService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [NoteService] (issue #563): note CRUD, private-note visibility (#73-style: a private
 * note is invisible to the other user → 404), the owner-only visibility change and the image gallery
 * DB path — all without an HTTP layer. The full HTTP + file-I/O contract stays covered by
 * NoteRouteTest / NoteImageRouteTest.
 */
class NoteServiceTest {

    private val service = NoteService()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:noteservice_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(NotesTable, NoteImagesTable, NoteAttachmentsTable) }
    }

    @Test
    fun `create then update round-trips and bumps content`() = runBlocking {
        val note = service.create("Titel", "Body", tags = listOf("a", "b"), folder = null, visibility = "SHARED", username = "alice")
        val id = UUID.fromString(note.id)
        assertEquals(listOf("a", "b"), note.tags)

        val r = service.update(id, UpdateNoteRequest(content = "Neu"), newVisibility = null, username = "bob")
        assertTrue(r is NoteService.UpdateResult.Success)
        assertEquals("Neu", r.note.content)
    }

    @Test
    fun `a private note is invisible to the other user`() = runBlocking {
        val note = service.create("Geheim", "x", null, null, visibility = "PRIVATE", username = "alice")
        val id = UUID.fromString(note.id)

        // bob cannot see or edit alice's private note → 404-mapped NotFound
        assertEquals(NoteService.UpdateResult.NotFound, service.update(id, UpdateNoteRequest(title = "hack"), null, "bob"))
        assertNull(service.delete(id, "bob"))
        // alice (the owner) still sees it in her list
        assertEquals(1, service.list("alice", query = null, folder = null).size)
        assertEquals(0, service.list("bob", query = null, folder = null).size)
    }

    @Test
    fun `only the owner may change visibility of a shared note`() = runBlocking {
        val note = service.create("Geteilt", "x", null, null, visibility = "SHARED", username = "alice")
        val id = UUID.fromString(note.id)

        // bob may edit content but not flip it private
        val forbidden = service.update(id, UpdateNoteRequest(visibility = "PRIVATE"), newVisibility = "PRIVATE", username = "bob")
        assertEquals(NoteService.UpdateResult.Forbidden, forbidden)
        // the owner may
        val ok = service.update(id, UpdateNoteRequest(visibility = "PRIVATE"), newVisibility = "PRIVATE", username = "alice")
        assertTrue(ok is NoteService.UpdateResult.Success)
    }

    @Test
    fun `search filters by title, content and tags`() = runBlocking {
        service.create("Einkauf", "Milch und Brot", listOf("haushalt"), null, "SHARED", "alice")
        service.create("Urlaub", "Strand", listOf("reise"), null, "SHARED", "alice")

        assertEquals(1, service.list("alice", query = "milch", folder = null).size)
        assertEquals(1, service.list("alice", query = "reise", folder = null).size)
        assertEquals(0, service.list("alice", query = "nichts", folder = null).size)
    }

    @Test
    fun `add and delete an image round-trips over the note aggregate`() = runBlocking {
        val note = service.create("Mit Bild", "x", null, null, "SHARED", "alice")
        val noteId = UUID.fromString(note.id)

        val upload = NoteService.StoredUpload(UUID.randomUUID(), "abc.jpg", "urlaub.jpg", "image/jpeg", 1234L)
        val withImage = service.addImage(noteId, "alice", upload)
        assertEquals(1, withImage?.images?.size)

        val outcome = service.deleteImage(noteId, upload.id, "alice")
        assertEquals("abc.jpg", outcome?.filename)
        assertEquals(0, outcome?.note?.images?.size)
    }

    @Test
    fun `adding an image to an unknown note returns null`() = runBlocking {
        val upload = NoteService.StoredUpload(UUID.randomUUID(), "abc.jpg", "x.jpg", "image/jpeg", 1L)
        assertNull(service.addImage(UUID.randomUUID(), "alice", upload))
    }
}
