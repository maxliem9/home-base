package com.homebase.android

import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.NoteImageDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.websocket.NotesWebSocketClient
import com.homebase.android.ui.notes.NoteImageUpload
import com.homebase.android.ui.notes.NotesViewModel
import com.homebase.android.ui.notes.SaveStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: NotesRepository
    private val wsEvents = MutableSharedFlow<NotesWebSocketClient.WsEvent>()

    /** Captures the WS "(re)connected" callback the VM registers, so a test can fire it like a reconnect (#269). */
    private val onConnectedSlot = slot<() -> Unit>()
    private fun fireWsReconnect() = onConnectedSlot.captured.invoke()

    private fun note(
        id: String = "1",
        title: String = "Titel",
        content: String = "",
        visibility: String = "SHARED",
        tags: List<String> = emptyList(),
        folder: String? = null,
    ) = NoteDto(
        id = id, title = title, content = content, tags = tags, folder = folder, visibility = visibility,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun image(id: String = "img-1", noteId: String = "1") = NoteImageDto(
        id = id, noteId = noteId, originalName = "p.png", contentType = "image/png",
        sizeBytes = 3, sortOrder = 0, createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        // Capture the reconnect callback the VM registers (#269) so tests can fire it.
        every { repository.setWebSocketOnConnected(capture(onConnectedSlot)) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = NotesViewModel(repository, "test-token")

    @Test
    fun `initial load populates notes`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(listOf(note()))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.notes.size)
    }

    @Test
    fun `initial load failure sets error`() = runTest {
        coEvery { repository.getNotes("") } returns Result.failure(RuntimeException("Network error"))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Network error", vm.uiState.value.error)
    }

    @Test
    fun `onQueryChange updates state and reloads with query`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())
        coEvery { repository.getNotes("pasta") } returns Result.success(listOf(note(title = "Pasta")))

        val vm = createVm()
        advanceUntilIdle()

        vm.onQueryChange("pasta")
        advanceUntilIdle()

        assertEquals("pasta", vm.uiState.value.query)
        assertEquals(1, vm.uiState.value.notes.size)
        coVerify { repository.getNotes("pasta") }
    }

    @Test
    fun `saveNote with null id creates and prepends note`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())
        val created = note(id = "2", title = "Neu")
        coEvery { repository.createNote(any()) } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        vm.saveNote(null, "Neu", "", emptyList(), "", "SHARED")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("Neu", vm.uiState.value.notes[0].title)
        // blank folder is sent as "" — the backend trims and maps it to null (mirrors web)
        coVerify { repository.createNote(CreateNoteRequest("Neu", "", emptyList(), "", "SHARED")) }
    }

    @Test
    fun `saveNote carries the trimmed folder on create`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())
        val created = note(id = "2", title = "Neu")
        coEvery { repository.createNote(any()) } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        vm.saveNote(null, "Neu", "", emptyList(), "  Reisen  ", "SHARED")
        advanceUntilIdle()

        coVerify { repository.createNote(CreateNoteRequest("Neu", "", emptyList(), "Reisen", "SHARED")) }
    }

    @Test
    fun `saveNote with blank title does nothing`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.saveNote(null, "   ", "", emptyList(), "", "SHARED")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createNote(any()) }
    }

    @Test
    fun `saveNote with id updates note in place`() = runTest {
        val original = note(id = "1", title = "Alt")
        coEvery { repository.getNotes("") } returns Result.success(listOf(original))
        val updated = original.copy(title = "Neu")
        coEvery { repository.updateNote(eq("1"), any()) } returns Result.success(updated)

        val vm = createVm()
        advanceUntilIdle()

        vm.saveNote("1", "Neu", "", emptyList(), "", "SHARED")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("Neu", vm.uiState.value.notes[0].title)
        coVerify { repository.updateNote("1", UpdateNoteRequest("Neu", "", emptyList(), "", "SHARED")) }
    }

    @Test
    fun `deleteNote removes it from list`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "1")))
        coEvery { repository.deleteNote("1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteNote("1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.notes.isEmpty())
    }

    @Test
    fun `clearError removes error from state`() = runTest {
        coEvery { repository.getNotes("") } returns Result.failure(RuntimeException("oops"))

        val vm = createVm()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `WS NoteCreated adds note without duplicate`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        val incoming = note(id = "ws-1", title = "Von Bob")
        wsEvents.emit(NotesWebSocketClient.WsEvent.NoteCreated(incoming))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("ws-1", vm.uiState.value.notes[0].id)
    }

    @Test
    fun `WS NoteUpdated upserts unseen note`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        // a note flipped private->shared arrives as an update the client has never seen
        wsEvents.emit(NotesWebSocketClient.WsEvent.NoteUpdated(note(id = "x", title = "Jetzt geteilt")))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("Jetzt geteilt", vm.uiState.value.notes[0].title)
    }

    @Test
    fun `WS NoteUpdated replaces existing note`() = runTest {
        val original = note(id = "1", title = "Alt")
        coEvery { repository.getNotes("") } returns Result.success(listOf(original))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(NotesWebSocketClient.WsEvent.NoteUpdated(original.copy(title = "Neu")))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("Neu", vm.uiState.value.notes[0].title)
    }

    @Test
    fun `uploadImage upserts the returned note`() = runTest {
        val original = note(id = "1")
        coEvery { repository.getNotes("") } returns Result.success(listOf(original))
        coEvery { repository.uploadImage(eq("1"), any(), any(), any()) } returns
            Result.success(original.copy(images = listOf(image())))

        val vm = createVm()
        advanceUntilIdle()

        vm.uploadImage("1", byteArrayOf(1, 2, 3), "p.png", "image/png")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes[0].images.size)
    }

    @Test
    fun `removeImage upserts the returned note`() = runTest {
        val withImage = note(id = "1").copy(images = listOf(image()))
        coEvery { repository.getNotes("") } returns Result.success(listOf(withImage))
        coEvery { repository.deleteImage("1", "img-1") } returns Result.success(note(id = "1"))

        val vm = createVm()
        advanceUntilIdle()

        vm.removeImage("1", "img-1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.notes[0].images.isEmpty())
    }

    @Test
    fun `uploadImages uploads each file and upserts every returned note`() = runTest {
        val original = note(id = "1")
        coEvery { repository.getNotes("") } returns Result.success(listOf(original))
        // each upload returns the note with one more image; the latest call's note wins in state
        coEvery { repository.uploadImage(eq("1"), any(), any(), any()) } returnsMany listOf(
            Result.success(original.copy(images = listOf(image(id = "a")))),
            Result.success(original.copy(images = listOf(image(id = "a"), image(id = "b")))),
        )

        val vm = createVm()
        advanceUntilIdle()

        vm.uploadImages(
            "1",
            listOf(
                NoteImageUpload(byteArrayOf(1), "a.png", "image/png"),
                NoteImageUpload(byteArrayOf(2), "b.png", "image/png"),
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.uploadImage(eq("1"), any(), any(), any()) }
        assertEquals(2, vm.uiState.value.notes[0].images.size)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `uploadImages surfaces the first failure but keeps the successful uploads`() = runTest {
        val original = note(id = "1")
        coEvery { repository.getNotes("") } returns Result.success(listOf(original))
        coEvery { repository.uploadImage(eq("1"), any(), any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("image must be JPEG, PNG, WebP or GIF")),
            Result.success(original.copy(images = listOf(image(id = "ok")))),
        )

        val vm = createVm()
        advanceUntilIdle()

        vm.uploadImages(
            "1",
            listOf(
                NoteImageUpload(byteArrayOf(1), "bad.tiff", "image/tiff"),
                NoteImageUpload(byteArrayOf(2), "ok.png", "image/png"),
            ),
        )
        advanceUntilIdle()

        // the good file still landed, and the first failure is reported
        assertEquals(1, vm.uiState.value.notes[0].images.size)
        assertEquals("image must be JPEG, PNG, WebP or GIF", vm.uiState.value.error)
    }

    @Test
    fun `uploadImages with no items does nothing`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "1")))

        val vm = createVm()
        advanceUntilIdle()

        vm.uploadImages("1", emptyList())
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.uploadImage(any(), any(), any(), any()) }
    }

    @Test
    fun `uploadImage failure sets error`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "1")))
        coEvery { repository.uploadImage(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("image exceeds the 10 MB limit"))

        val vm = createVm()
        advanceUntilIdle()

        vm.uploadImage("1", byteArrayOf(1), "p.png", "image/png")
        advanceUntilIdle()

        assertEquals("image exceeds the 10 MB limit", vm.uiState.value.error)
    }

    @Test
    fun `WS NoteDeleted removes note`() = runTest {
        val existing = note(id = "1")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(NotesWebSocketClient.WsEvent.NoteDeleted(existing))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.notes.isEmpty())
    }

    // --- #269: re-sync on WS reconnect / app resume / pull-to-refresh ---

    @Test
    fun `WS reconnect refetches the notes list`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "remote", title = "Von Web")))
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("Von Web", vm.uiState.value.notes[0].title)
    }

    @Test
    fun `WS reconnect refetches with the active query`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())
        coEvery { repository.getNotes("pasta") } returns Result.success(listOf(note(title = "Pasta")))

        val vm = createVm()
        advanceUntilIdle()
        vm.onQueryChange("pasta")
        advanceUntilIdle()

        fireWsReconnect()
        advanceUntilIdle()

        // The reconnect re-sync must use the live query, not the empty one.
        coVerify(atLeast = 2) { repository.getNotes("pasta") }
        assertEquals(1, vm.uiState.value.notes.size)
    }

    @Test
    fun `WS reconnect re-sync keeps existing notes on a transient failure`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "1", title = "Da")))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.notes.size)

        coEvery { repository.getNotes("") } returns Result.failure(RuntimeException("down"))
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notes.size)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `ensureConnected reconnects and re-syncs from the server`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "bg", title = "Im Hintergrund")))
        vm.ensureConnected()
        advanceUntilIdle()

        coVerify { repository.ensureWebSocketConnected() }
        assertEquals(1, vm.uiState.value.notes.size)
    }

    @Test
    fun `refresh refetches without ever setting the loading flag`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getNotes("") } returns Result.success(listOf(note(id = "r", title = "Neu")))
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.notes.size)
    }

    // --- #309/#310: editor auto-save (debounce, id-capture, no-duplicate-create, dirty-check) ---

    @Test
    fun `editing a new note debounces then creates exactly once and captures the id`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())
        val created = note(id = "srv-1", title = "Neu")
        coEvery { repository.createNote(any()) } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(null)
        vm.updateEditor(title = "Neu")
        // Before the debounce window elapses nothing is sent.
        advanceTimeBy(500)
        runCurrent()
        coVerify(exactly = 0) { repository.createNote(any()) }
        assertEquals(SaveStatus.IDLE, vm.editorState.value?.status)

        // After the window the create fires once and the returned id is captured into the editor.
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.createNote(CreateNoteRequest("Neu", "", emptyList(), "", "SHARED")) }
        assertEquals("srv-1", vm.editorState.value?.noteId)
        assertEquals(SaveStatus.SAVED, vm.editorState.value?.status)
        assertEquals(1, vm.uiState.value.notes.size)
    }

    @Test
    fun `a second edit after the first create updates the captured id (no duplicate note)`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())
        coEvery { repository.createNote(any()) } returns Result.success(note(id = "srv-1", title = "Neu"))
        coEvery { repository.updateNote(eq("srv-1"), any()) } returns
            Result.success(note(id = "srv-1", title = "Neu", content = "mehr"))

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(null)
        vm.updateEditor(title = "Neu")
        advanceUntilIdle() // first create

        vm.updateEditor(content = "mehr")
        advanceUntilIdle() // should UPDATE the captured id, not create again

        coVerify(exactly = 1) { repository.createNote(any()) }
        coVerify(exactly = 1) { repository.updateNote("srv-1", UpdateNoteRequest("Neu", "mehr", emptyList(), "", "SHARED")) }
        assertEquals(1, vm.uiState.value.notes.size) // exactly one note, not two
    }

    @Test
    fun `a blank-title new note is never created`() = runTest {
        coEvery { repository.getNotes("") } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(null)
        // Type only body content — still no title.
        vm.updateEditor(content = "nur Inhalt")
        advanceUntilIdle()
        vm.flushEditorSave() // even an explicit flush must not create an untitled note
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createNote(any()) }
        assertEquals(SaveStatus.IDLE, vm.editorState.value?.status)
    }

    @Test
    fun `opening an existing note and leaving without changes saves nothing (dirty check)`() = runTest {
        val existing = note(id = "1", title = "Da", content = "Body", tags = listOf("a"), folder = "Reisen")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        // Re-set every field to its current value: a no-op change must not dirty the note.
        vm.updateEditor(title = "Da", content = "Body", tags = listOf("a"), folder = "Reisen", visibility = "SHARED")
        advanceUntilIdle()
        vm.closeEditor()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateNote(any(), any()) }
    }

    @Test
    fun `leaving the editor flushes a pending edit immediately`() = runTest {
        val existing = note(id = "1", title = "Alt")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))
        coEvery { repository.updateNote(eq("1"), any()) } returns Result.success(existing.copy(title = "Neu"))

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        vm.updateEditor(title = "Neu")
        // Close BEFORE the debounce elapses — the close must still persist the change.
        advanceTimeBy(200)
        runCurrent()
        vm.closeEditor()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateNote("1", UpdateNoteRequest("Neu", "", emptyList(), "", "SHARED")) }
        assertNull(vm.editorState.value) // editor closed
    }

    @Test
    fun `a WS update for the open note does not clobber the unsaved draft`() = runTest {
        val existing = note(id = "1", title = "Alt", content = "Original")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))
        // The eventual (debounced) auto-save just needs to succeed so teardown stays clean; this
        // test is about the WS echo NOT overwriting the draft, not about the save itself.
        coEvery { repository.updateNote(eq("1"), any()) } returns Result.success(existing)

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        vm.updateEditor(content = "Meine ungespeicherten Tasten")
        // A partner's edit (or our own save echo) arrives on the socket for the same note.
        wsEvents.emit(NotesWebSocketClient.WsEvent.NoteUpdated(existing.copy(content = "Server-Version")))
        advanceTimeBy(100)
        runCurrent()

        // The live draft text is preserved; the list copy may differ.
        assertEquals("Meine ungespeicherten Tasten", vm.editorState.value?.content)
    }

    @Test
    fun `switching notes flushes the current draft then opens the target`() = runTest {
        val a = note(id = "a", title = "A")
        val b = note(id = "b", title = "B")
        coEvery { repository.getNotes("") } returns Result.success(listOf(a, b))
        coEvery { repository.updateNote(eq("a"), any()) } returns Result.success(a.copy(title = "A2"))

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(a)
        vm.updateEditor(title = "A2")
        vm.switchEditorTo(b)
        advanceUntilIdle()

        // A's pending edit was saved, and the editor now shows B.
        coVerify(exactly = 1) { repository.updateNote("a", UpdateNoteRequest("A2", "", emptyList(), "", "SHARED")) }
        assertEquals("b", vm.editorState.value?.noteId)
        assertEquals("B", vm.editorState.value?.title)
    }

    @Test
    fun `deleteEditorNote deletes the open note and closes the editor`() = runTest {
        val existing = note(id = "1")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))
        coEvery { repository.deleteNote("1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        vm.deleteEditorNote()
        advanceUntilIdle()

        coVerify { repository.deleteNote("1") }
        assertNull(vm.editorState.value)
        assertTrue(vm.uiState.value.notes.isEmpty())
    }

    @Test
    fun `an edit during an in-flight save is not lost — the loop re-saves the latest`() = runTest {
        val existing = note(id = "1", title = "Alt")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))
        // First update suspends (in flight) long enough for a second edit to land; both must persist.
        coEvery { repository.updateNote(eq("1"), any()) } coAnswers {
            kotlinx.coroutines.delay(50)
            Result.success(existing)
        }

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        vm.updateEditor(content = "erste Änderung")
        advanceTimeBy(1000) // debounce elapses → first save starts and suspends in the mock
        runCurrent()
        vm.updateEditor(content = "zweite Änderung") // lands WHILE the first save is in flight
        advanceUntilIdle() // first save completes, loop notices the new draft and saves again

        // Two updates ran (no dropped edit) and the last one carried the newest content.
        coVerify(exactly = 2) { repository.updateNote(eq("1"), any()) }
        coVerify(exactly = 1) { repository.updateNote("1", UpdateNoteRequest("Alt", "zweite Änderung", emptyList(), "", "SHARED")) }
    }

    @Test
    fun `closing during an in-flight save still persists the final edit`() = runTest {
        val existing = note(id = "1", title = "Alt")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))
        coEvery { repository.updateNote(eq("1"), any()) } coAnswers {
            kotlinx.coroutines.delay(50)
            Result.success(existing)
        }

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        vm.updateEditor(content = "A")
        advanceTimeBy(1000)
        runCurrent() // first save (content "A") in flight
        vm.updateEditor(content = "B") // newer edit while saving
        vm.closeEditor() // back press mid-save: must flush B before clearing
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateNote("1", UpdateNoteRequest("Alt", "B", emptyList(), "", "SHARED")) }
        assertNull(vm.editorState.value)
    }

    @Test
    fun `a failed save surfaces an error and an ERROR status`() = runTest {
        val existing = note(id = "1", title = "Alt")
        coEvery { repository.getNotes("") } returns Result.success(listOf(existing))
        coEvery { repository.updateNote(eq("1"), any()) } returns Result.failure(RuntimeException("down"))

        val vm = createVm()
        advanceUntilIdle()

        vm.openEditor(existing)
        vm.updateEditor(title = "Neu")
        advanceUntilIdle()

        assertEquals(SaveStatus.ERROR, vm.editorState.value?.status)
        assertEquals("down", vm.uiState.value.error)
    }
}
