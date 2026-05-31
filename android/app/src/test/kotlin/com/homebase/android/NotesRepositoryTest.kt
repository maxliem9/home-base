package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.websocket.NotesWebSocketClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var wsClient: NotesWebSocketClient
    private lateinit var repository: NotesRepository

    private fun note(id: String = "1", title: String = "Titel") = NoteDto(
        id = id, title = title, content = "", tags = emptyList(), visibility = "SHARED",
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        api = mockk()
        wsClient = mockk(relaxed = true)
        every { wsClient.events } returns emptyFlow()
        repository = NotesRepository(api, wsClient)
    }

    @Test
    fun `getNotes returns api result on success`() = runTest {
        val notes = listOf(note("1"), note("2"))
        coEvery { api.getNotes(null) } returns notes

        val result = repository.getNotes()

        assertTrue(result.isSuccess)
        assertEquals(notes, result.getOrNull())
    }

    @Test
    fun `getNotes passes non-blank query through`() = runTest {
        coEvery { api.getNotes("pasta") } returns emptyList()

        repository.getNotes("pasta")

        coVerify { api.getNotes("pasta") }
    }

    @Test
    fun `getNotes converts blank query to null`() = runTest {
        coEvery { api.getNotes(null) } returns emptyList()

        repository.getNotes("   ")

        coVerify { api.getNotes(null) }
    }

    @Test
    fun `getNotes returns failure on exception`() = runTest {
        coEvery { api.getNotes(null) } throws RuntimeException("Network error")

        val result = repository.getNotes()

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `createNote delegates to api`() = runTest {
        val request = CreateNoteRequest(title = "Neu", visibility = "PRIVATE")
        val expected = note(title = "Neu")
        coEvery { api.createNote(request) } returns expected

        val result = repository.createNote(request)

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify { api.createNote(request) }
    }

    @Test
    fun `updateNote delegates to api`() = runTest {
        val request = UpdateNoteRequest(title = "Geändert")
        val updated = note(title = "Geändert")
        coEvery { api.updateNote("1", request) } returns updated

        val result = repository.updateNote("1", request)

        assertTrue(result.isSuccess)
        assertEquals(updated, result.getOrNull())
    }

    @Test
    fun `deleteNote delegates to api`() = runTest {
        coEvery { api.deleteNote("1") } returns Unit

        val result = repository.deleteNote("1")

        assertTrue(result.isSuccess)
        coVerify { api.deleteNote("1") }
    }

    @Test
    fun `deleteNote returns failure on api exception`() = runTest {
        coEvery { api.deleteNote("1") } throws RuntimeException("Not found")

        val result = repository.deleteNote("1")

        assertTrue(result.isFailure)
    }
}
