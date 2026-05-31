package com.homebase.android

import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.inbox.InboxViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TodoRepository
    private val wsEvents = MutableSharedFlow<TodoWebSocketClient.WsEvent>()

    private fun todo(id: String = "1", title: String = "Test", status: String = "INBOX") = TodoDto(
        id = id, title = title, status = status,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(): InboxViewModel = InboxViewModel(repository, "test-token")

    @Test
    fun `initial load populates todos`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo()))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.todos.size)
        assertEquals("Test", vm.uiState.value.todos[0].title)
    }

    @Test
    fun `initial load failure sets error`() = runTest {
        coEvery { repository.getTodos() } returns Result.failure(RuntimeException("Network error"))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Network error", vm.uiState.value.error)
    }

    @Test
    fun `createTodo prepends new todo to list`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        val newTodo = todo(id = "2", title = "Buy milk")
        coEvery { repository.createTodo("Buy milk") } returns Result.success(newTodo)

        val vm = createVm()
        advanceUntilIdle()

        vm.createTodo("Buy milk")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.todos.size)
        assertEquals("Buy milk", vm.uiState.value.todos[0].title)
    }

    @Test
    fun `createTodo with blank title does nothing`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.createTodo("   ")
        advanceUntilIdle()

        verify(exactly = 0) { repository.createTodo(any()) }
        assertTrue(vm.uiState.value.todos.isEmpty())
    }

    @Test
    fun `markDone replaces todo in list`() = runTest {
        val original = todo(id = "1", status = "INBOX")
        val updated = original.copy(status = "DONE")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", UpdateTodoRequest(status = "DONE")) } returns Result.success(updated)

        val vm = createVm()
        advanceUntilIdle()

        vm.markDone("1")
        advanceUntilIdle()

        assertEquals("DONE", vm.uiState.value.todos[0].status)
    }

    @Test
    fun `deleteTodo removes it from list`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "1")))
        coEvery { repository.deleteTodo("1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteTodo("1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.todos.isEmpty())
    }

    @Test
    fun `clearError removes error from state`() = runTest {
        coEvery { repository.getTodos() } returns Result.failure(RuntimeException("oops"))

        val vm = createVm()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `WS TodoCreated event adds todo without duplicate`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        val incoming = todo(id = "ws-1", title = "WS todo")
        wsEvents.emit(TodoWebSocketClient.WsEvent.TodoCreated(incoming))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.todos.size)
        assertEquals("ws-1", vm.uiState.value.todos[0].id)
    }

    @Test
    fun `WS TodoCreated event does not add duplicate`() = runTest {
        val existing = todo(id = "1")
        coEvery { repository.getTodos() } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TodoWebSocketClient.WsEvent.TodoCreated(existing))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.todos.size)
    }

    @Test
    fun `WS TodoUpdated event updates todo in place`() = runTest {
        val original = todo(id = "1", title = "Old")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TodoWebSocketClient.WsEvent.TodoUpdated(original.copy(title = "New")))
        advanceUntilIdle()

        assertEquals("New", vm.uiState.value.todos[0].title)
    }
}
