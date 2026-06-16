package com.homebase.android

import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.repository.AbsenceRepository
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import com.homebase.android.ui.abwesenheit.AbsenceViewModel
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

/**
 * Reconnect / resume / pull-to-refresh re-sync of the Familienkalender (#269). The planner is a
 * single snapshot, so a re-sync simply re-reads it; these tests mirror the time/todos/notes ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbsenceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AbsenceRepository
    private val wsEvents = MutableSharedFlow<AbsenceWebSocketClient.WsEvent>()

    /** Captures the WS "(re)connected" callback the VM registers, so a test can fire it like a reconnect (#269). */
    private val onConnectedSlot = slot<() -> Unit>()
    private fun fireWsReconnect() = onConnectedSlot.captured.invoke()

    private fun state(users: List<String> = listOf("alice")) = AbsenceStateDto(users = users)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        // Capture the reconnect callback the VM registers (#269) so tests can fire it.
        every { repository.setWebSocketOnConnected(capture(onConnectedSlot)) } returns Unit
        coEvery { repository.getState() } returns Result.success(state())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = AbsenceViewModel(repository, "test-token")

    @Test
    fun `initial load populates the snapshot`() = runTest {
        coEvery { repository.getState() } returns Result.success(state(users = listOf("alice", "bob")))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf("alice", "bob"), vm.uiState.value.data.users)
    }

    @Test
    fun `initial load failure sets error`() = runTest {
        coEvery { repository.getState() } returns Result.failure(RuntimeException("Network error"))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Network error", vm.uiState.value.error)
    }

    // --- #269: re-sync on WS reconnect / app resume / pull-to-refresh ---

    @Test
    fun `WS reconnect re-reads the snapshot`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getState() } returns Result.success(state(users = listOf("alice", "bob")))
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(listOf("alice", "bob"), vm.uiState.value.data.users)
    }

    @Test
    fun `WS reconnect re-sync keeps existing data on a transient failure`() = runTest {
        coEvery { repository.getState() } returns Result.success(state(users = listOf("alice", "bob")))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.data.users.size)

        coEvery { repository.getState() } returns Result.failure(RuntimeException("down"))
        fireWsReconnect()
        advanceUntilIdle()

        // The silent re-sync must not blank the planner or surface an error.
        assertEquals(2, vm.uiState.value.data.users.size)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `ensureConnected reconnects and re-reads the snapshot`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getState() } returns Result.success(state(users = listOf("alice", "carol")))
        vm.ensureConnected()
        advanceUntilIdle()

        coVerify { repository.ensureWebSocketConnected() }
        assertEquals(listOf("alice", "carol"), vm.uiState.value.data.users)
    }

    @Test
    fun `refresh re-reads without ever setting the loading flag`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getState() } returns Result.success(state(users = listOf("alice", "dave")))
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf("alice", "dave"), vm.uiState.value.data.users)
    }
}
