package com.homebase.android

import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.NoteImageDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.websocket.NotesWebSocketClient
import com.homebase.android.ui.notes.NotesViewModel
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
    ) = NoteDto(
        id = id, title = title, content = content, tags = emptyList(), visibility = visibility,
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
}
