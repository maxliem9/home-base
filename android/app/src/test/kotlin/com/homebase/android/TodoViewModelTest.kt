package com.homebase.android

import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.aufgaben.INBOX_TAB_ID
import com.homebase.android.ui.aufgaben.TodoViewModel
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
class TodoViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TodoRepository
    private val wsEvents = MutableSharedFlow<TodoWebSocketClient.WsEvent>()

    /** Captures the WS "(re)connected" callback the VM registers, so a test can fire it like a reconnect (#269). */
    private val onConnectedSlot = slot<() -> Unit>()
    private fun fireWsReconnect() = onConnectedSlot.captured.invoke()

    private fun todo(id: String = "1", title: String = "Test", status: String = "INBOX", listId: String? = null) = TodoDto(
        id = id, title = title, status = status, listId = listId,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    private fun list(id: String, name: String = "Liste $id") = TodoListDto(
        id = id, name = name, visibility = "SHARED",
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        // Capture the reconnect callback the VM registers (#269) so tests can fire it.
        every { repository.setWebSocketOnConnected(capture(onConnectedSlot)) } returns Unit
        // load() fetches both lists and todos; default lists to empty unless a test overrides.
        coEvery { repository.getLists() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(): TodoViewModel = TodoViewModel(repository, "test-token")

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
    fun `addTodo prepends new todo to list`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        val newTodo = todo(id = "2", title = "Buy milk")
        coEvery { repository.createTodo(CreateTodoRequest("Buy milk")) } returns Result.success(newTodo)

        val vm = createVm()
        advanceUntilIdle()

        vm.addTodo("Buy milk")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.todos.size)
        assertEquals("Buy milk", vm.uiState.value.todos[0].title)
    }

    @Test
    fun `addTodo with blank title does nothing`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.addTodo("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createTodo(any()) }
        assertTrue(vm.uiState.value.todos.isEmpty())
    }

    @Test
    fun `toggleDone marks an open todo done`() = runTest {
        val original = todo(id = "1", status = "INBOX")
        val updated = original.copy(status = "DONE")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", UpdateTodoRequest(status = "DONE")) } returns Result.success(updated)

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleDone(original)
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

    // --- #288: silent mutations now surface via the global error (screen toast) ---

    @Test
    fun `addTodo failure sets the global error`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.failure(RuntimeException("Quick-add kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addTodo("Neu")
        advanceUntilIdle()

        assertEquals("Quick-add kaputt", vm.uiState.value.error)
    }

    @Test
    fun `toggleDone failure sets the global error`() = runTest {
        val original = todo(id = "1", status = "INBOX")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns Result.failure(RuntimeException("Abhaken kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleDone(original)
        advanceUntilIdle()

        assertEquals("Abhaken kaputt", vm.uiState.value.error)
    }

    @Test
    fun `deleteTodo failure sets the global error`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "1")))
        coEvery { repository.deleteTodo("1") } returns Result.failure(RuntimeException("Löschen kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteTodo("1")
        advanceUntilIdle()

        assertEquals("Löschen kaputt", vm.uiState.value.error)
    }

    @Test
    fun `subtask mutation failure sets the global error`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "1")))
        coEvery { repository.addSubtask("1", "Sub") } returns Result.failure(RuntimeException("Subtask kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addSubtask("1", "Sub")
        advanceUntilIdle()

        assertEquals("Subtask kaputt", vm.uiState.value.error)
    }

    // --- #277/#288 coordination: the edit-sheet save fns return the message but must NOT also
    //     set the global error, or an edit-sheet failure would double-notify (in-sheet + toast). ---

    @Test
    fun `createTodo failure returns the message without setting the global error`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.failure(RuntimeException("Sheet kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        val result = vm.createTodo("Neu")

        assertEquals("Sheet kaputt", result.exceptionOrNull()?.message)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `saveTodo failure returns the message without setting the global error`() = runTest {
        val original = todo(id = "1", status = "INBOX")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns Result.failure(RuntimeException("Sheet-Save kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        val result = vm.saveTodo("1", UpdateTodoRequest(status = "DONE"))

        assertEquals("Sheet-Save kaputt", result.exceptionOrNull()?.message)
        assertNull(vm.uiState.value.error)
    }

    // --- Inbox tab (issue #77, semantics decided in #71) ---

    @Test
    fun `inbox shows status-INBOX todos from lists plus all list-less todos`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(
            listOf(
                todo(id = "1", status = "INBOX", listId = "a"), // unplanned list todo → in the inbox
                todo(id = "2", status = "PLANNED", listId = "a"), // planned list todo → not in the inbox
                todo(id = "3", status = "PLANNED"), // list-less → in the inbox regardless of status
                todo(id = "4", status = "DONE"), // list-less done → in the inbox (done section)
            ),
        )

        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(INBOX_TAB_ID)

        val state = vm.uiState.value
        assertTrue(state.inboxActive)
        assertNull(state.activeList)
        assertEquals(listOf("1", "3", "4"), state.visibleTodos.map { it.id })
    }

    @Test
    fun `inbox badge counts only status-INBOX todos`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(
            listOf(
                todo(id = "1", status = "INBOX", listId = "a"), // counts although it sits in a list
                todo(id = "2", status = "INBOX"),
                todo(id = "3", status = "PLANNED"), // shown in the inbox tab, but planned → not counted
            ),
        )

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.inboxCount)
    }

    @Test
    fun `inbox is the default tab when no lists exist`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "1")))

        val vm = createVm()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.inboxActive)
        assertNull(state.activeList)
        assertEquals(listOf("1"), state.visibleTodos.map { it.id })
    }

    @Test
    fun `first list is the default tab and no longer surfaces list-less todos`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(
            listOf(
                todo(id = "1", listId = "a"),
                todo(id = "2"), // list-less → only reachable via the inbox tab now
            ),
        )

        val vm = createVm()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.inboxActive)
        assertEquals("a", state.activeList?.id)
        assertEquals(listOf("1"), state.visibleTodos.map { it.id })
    }

    @Test
    fun `addTodo in the inbox tab posts without listId`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.success(todo(id = "2", title = "Neu"))

        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(INBOX_TAB_ID)

        vm.addTodo("Neu")
        advanceUntilIdle()

        coVerify { repository.createTodo(CreateTodoRequest(title = "Neu", listId = null)) }
    }

    @Test
    fun `addTodo in a list tab posts with that listId`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.success(todo(id = "2", title = "Neu", listId = "a"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addTodo("Neu")
        advanceUntilIdle()

        coVerify { repository.createTodo(CreateTodoRequest(title = "Neu", listId = "a")) }
    }

    // --- Planen aus der Inbox (issue #77) ---

    @Test
    fun `updateTodo files a list-less todo into the picked list`() = runTest {
        val original = todo(id = "1")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns
            Result.success(original.copy(status = "PLANNED", assignee = "alice", listId = "a"))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateTodo("1", UpdateTodoRequest(status = "PLANNED", assignee = "alice"), targetListId = "a")
        advanceUntilIdle()

        coVerify {
            repository.updateTodo("1", UpdateTodoRequest(status = "PLANNED", assignee = "alice", listId = "a"))
        }
    }

    @Test
    fun `updateTodo ignores a stale list pick when the todo is already in a list`() = runTest {
        // the partner filed the todo into list b while the plan sheet was open (#69 guard)
        val original = todo(id = "1", listId = "b")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns Result.success(original.copy(status = "PLANNED"))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateTodo("1", UpdateTodoRequest(status = "PLANNED"), targetListId = "a")
        advanceUntilIdle()

        coVerify { repository.updateTodo("1", UpdateTodoRequest(status = "PLANNED")) }
    }

    @Test
    fun `updateTodo without a list pick leaves listId untouched`() = runTest {
        val original = todo(id = "1")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns Result.success(original.copy(status = "PLANNED"))

        val vm = createVm()
        advanceUntilIdle()

        // „Bleibt in der Inbox" — no listId on the request
        vm.updateTodo("1", UpdateTodoRequest(status = "PLANNED"), targetListId = null)
        advanceUntilIdle()

        coVerify { repository.updateTodo("1", UpdateTodoRequest(status = "PLANNED")) }
    }

    @Test
    fun `planning into a list removes the todo from the inbox`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        val original = todo(id = "1")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns
            Result.success(original.copy(status = "PLANNED", assignee = "alice", listId = "a"))

        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(INBOX_TAB_ID)

        vm.updateTodo("1", UpdateTodoRequest(status = "PLANNED", assignee = "alice"), targetListId = "a")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.visibleTodos.isEmpty()) // left the inbox …
        assertEquals(0, state.inboxCount) // … and the badge
    }

    @Test
    fun `WS TodoCreated event adds todo`() = runTest {
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

    @Test
    fun `WS TodoDeleted event removes todo`() = runTest {
        val original = todo(id = "1")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TodoWebSocketClient.WsEvent.TodoDeleted(original))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.todos.isEmpty())
    }

    // --- #269: re-sync on WS reconnect / app resume / pull-to-refresh ---

    @Test
    fun `WS reconnect refetches lists and todos`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()
        // load() fetched once; the first onConnected re-sync overlaps it (cheap), so allow >=2.
        coVerify(atLeast = 1) { repository.getTodos() }

        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "remote", title = "Von Web")))
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.todos.size)
        assertEquals("Von Web", vm.uiState.value.todos[0].title)
    }

    @Test
    fun `WS reconnect re-sync keeps existing todos on a transient failure`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "1", title = "Da")))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.todos.size)

        // Socket reconnects but the refetch fails (still flaky) — must not blank the list or error.
        coEvery { repository.getLists() } returns Result.failure(RuntimeException("down"))
        coEvery { repository.getTodos() } returns Result.failure(RuntimeException("down"))
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.todos.size)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `ensureConnected reconnects and re-syncs from the server`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "bg", title = "Im Hintergrund")))
        vm.ensureConnected()
        advanceUntilIdle()

        coVerify { repository.ensureWebSocketConnected() }
        assertEquals(1, vm.uiState.value.todos.size)
        assertEquals("Im Hintergrund", vm.uiState.value.todos[0].title)
    }

    @Test
    fun `refresh refetches without ever setting the loading flag`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "r", title = "Neu")))
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.todos.size)
    }

    @Test
    fun `VM registers a reconnect callback on construction`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        createVm()
        advanceUntilIdle()

        // The VM wired itself to the channel's onConnected (#269) — captured by onConnectedSlot.
        assertTrue(onConnectedSlot.isCaptured)
    }
}
