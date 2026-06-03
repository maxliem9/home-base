package com.homebase.android

import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.websocket.TimeWebSocketClient
import com.homebase.android.ui.time.TimeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TimeRepository
    private val wsEvents = MutableSharedFlow<TimeWebSocketClient.WsEvent>()

    private fun project(id: String = "p1", name: String = "Arbeit", archived: Boolean = false) =
        ProjectDto(id = id, name = name, color = "#4F46E5", archived = archived, createdBy = "alice", createdAt = "2026-01-01T00:00:00Z")

    private fun entry(
        id: String = "e1",
        projectId: String = "p1",
        userId: String = "alice",
        stoppedAt: String? = "2026-06-03T09:00:00Z",
        durationSeconds: Long? = 3600,
    ) = TimeEntryDto(
        id = id, projectId = projectId, userId = userId,
        startedAt = "2026-06-03T08:00:00Z", stoppedAt = stoppedAt,
        description = null, durationSeconds = durationSeconds,
        createdAt = "2026-06-03T08:00:00Z", updatedAt = "2026-06-03T09:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        coEvery { repository.getProjects() } returns Result.success(emptyList())
        coEvery { repository.getEntries() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(username: String? = "alice") = TimeViewModel(repository, "test-token", username)

    @Test
    fun `initial load populates projects and entries`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project()))
        coEvery { repository.getEntries() } returns Result.success(listOf(entry()))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals(1, vm.uiState.value.entries.size)
        assertNull(vm.uiState.value.running)
    }

    @Test
    fun `running timer is derived from open entry for this user`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(
            listOf(entry(id = "open", stoppedAt = null, durationSeconds = null))
        )

        val vm = createVm()
        advanceUntilIdle()

        assertEquals("open", vm.uiState.value.running?.id)
    }

    @Test
    fun `open entry from other user is not treated as my running timer`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(
            listOf(entry(id = "bobs", userId = "bob", stoppedAt = null, durationSeconds = null))
        )

        val vm = createVm(username = "alice")
        advanceUntilIdle()

        assertNull(vm.uiState.value.running)
    }

    @Test
    fun `startTimer upserts returned entry and sets running`() = runTest {
        val started = entry(id = "new", stoppedAt = null, durationSeconds = null)
        coEvery { repository.startTimer("p1", null) } returns Result.success(started)

        val vm = createVm()
        advanceUntilIdle()

        vm.startTimer("p1", null)
        advanceUntilIdle()

        assertEquals("new", vm.uiState.value.running?.id)
        assertEquals(1, vm.uiState.value.entries.size)
    }

    @Test
    fun `stopTimer clears running by updating the entry`() = runTest {
        val open = entry(id = "open", stoppedAt = null, durationSeconds = null)
        coEvery { repository.getEntries() } returns Result.success(listOf(open))
        coEvery { repository.stopTimer() } returns Result.success(open.copy(stoppedAt = "2026-06-03T09:00:00Z", durationSeconds = 3600))

        val vm = createVm()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.running)

        vm.stopTimer()
        advanceUntilIdle()

        assertNull(vm.uiState.value.running)
        assertEquals(1, vm.uiState.value.entries.size)
    }

    @Test
    fun `addProject appends created project`() = runTest {
        coEvery { repository.createProject("Garten", "#10B981") } returns Result.success(project(id = "p2", name = "Garten"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addProject("Garten", "#10B981")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals("Garten", vm.uiState.value.projects[0].name)
    }

    @Test
    fun `addProject with blank name does nothing`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.addProject("   ", "#10B981")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createProject(any(), any()) }
    }

    @Test
    fun `deleteEntry removes it`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        coEvery { repository.deleteEntry("e1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteEntry("e1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun `setArchived updates project flag in place`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1")))
        coEvery { repository.setArchived("p1", true) } returns Result.success(project(id = "p1", archived = true))

        val vm = createVm()
        advanceUntilIdle()

        vm.setArchived("p1", true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.projects[0].archived)
        assertTrue(vm.uiState.value.activeProjects.isEmpty())
    }

    @Test
    fun `WS EntryCreated adds entry`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TimeWebSocketClient.WsEvent.EntryCreated(entry(id = "ws-1")))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals("ws-1", vm.uiState.value.entries[0].id)
    }

    @Test
    fun `WS ProjectUpdated replaces project`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1", name = "Alt")))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TimeWebSocketClient.WsEvent.ProjectUpdated(project(id = "p1", name = "Neu")))
        advanceUntilIdle()

        assertEquals("Neu", vm.uiState.value.projects[0].name)
    }
}
