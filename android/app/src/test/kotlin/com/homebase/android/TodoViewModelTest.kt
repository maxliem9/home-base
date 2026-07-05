package com.homebase.android

import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.model.DoneWindowConfigResponse
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.aufgaben.ALL_TAB_ID
import com.homebase.android.ui.aufgaben.DONE_TAB_ID
import com.homebase.android.ui.aufgaben.DONE_WINDOW_DAYS
import com.homebase.android.ui.aufgaben.INBOX_TAB_ID
import com.homebase.android.ui.aufgaben.TODAY_TAB_ID
import com.homebase.android.ui.aufgaben.TOMORROW_TAB_ID
import com.homebase.android.ui.aufgaben.TodoViewModel
import com.homebase.android.ui.aufgaben.TodosFocus
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
    private lateinit var configRepository: ConfigRepository
    private val wsEvents = MutableSharedFlow<TodoWebSocketClient.WsEvent>()

    /** Captures the WS "(re)connected" callback the VM registers, so a test can fire it like a reconnect (#269). */
    private val onConnectedSlot = slot<() -> Unit>()
    private fun fireWsReconnect() = onConnectedSlot.captured.invoke()

    private fun todo(
        id: String = "1",
        title: String = "Test",
        status: String = "INBOX",
        listId: String? = null,
        dueDate: String? = null,
        doneAt: String? = null,
    ) = TodoDto(
        id = id, title = title, status = status, listId = listId, dueDate = dueDate, doneAt = doneAt,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
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
        // The VM reads the configurable done-window (#356) on init; default to 14 so the windowed
        // smart-view tests keep their original boundaries (DONE_WINDOW_DAYS-relative fixtures).
        configRepository = mockk(relaxed = true)
        coEvery { configRepository.getDoneWindow() } returns Result.success(DoneWindowConfigResponse(DONE_WINDOW_DAYS))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(): TodoViewModel = TodoViewModel(repository, configRepository, "test-token")

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

    // --- Quick-Add „Details"-Panel (issue #393) ---

    @Test
    fun `addPlannedTodo sends the Details fields in one create and returns true`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        val created = todo(id = "2", title = "Steuer", status = "PLANNED", listId = "a", dueDate = "2026-07-01")
        coEvery { repository.createTodo(any()) } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        // addPlannedTodo is suspend and sequential (no child coroutine), so awaiting it directly in
        // the runTest body returns the success flag.
        val ok = vm.addPlannedTodo("Steuer", description = "ELSTER", assignees = listOf("alice"), dueDate = "2026-07-01", priority = "HIGH")

        assertTrue(ok)
        // single create carrying all the Details fields + the active list; status is server-derived
        coVerify {
            repository.createTodo(
                CreateTodoRequest(
                    title = "Steuer",
                    description = "ELSTER",
                    assignees = listOf("alice"),
                    dueDate = "2026-07-01",
                    priority = "HIGH",
                    listId = "a",
                ),
            )
        }
        assertEquals("Steuer", vm.uiState.value.todos[0].title)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `addPlannedTodo blank or empty Details collapse to a title-only inbox create`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.success(todo(id = "2", title = "Neu"))

        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(INBOX_TAB_ID)

        // blank description / empty strings / no assignees must be dropped, not sent → plain INBOX create
        vm.addPlannedTodo("Neu", description = "   ", assignees = emptyList(), dueDate = "", priority = "")

        coVerify { repository.createTodo(CreateTodoRequest(title = "Neu", listId = null)) }
    }

    @Test
    fun `addPlannedTodo failure surfaces the global error and returns false`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.failure(RuntimeException("Quick-add kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        val ok = vm.addPlannedTodo("Neu", assignees = listOf("alice"))

        assertFalse(ok)
        assertEquals("Quick-add kaputt", vm.uiState.value.error)
    }

    // --- Planen aus der Inbox (issue #77) ---

    @Test
    fun `updateTodo files a list-less todo into the picked list`() = runTest {
        val original = todo(id = "1")
        coEvery { repository.getTodos() } returns Result.success(listOf(original))
        coEvery { repository.updateTodo("1", any()) } returns
            Result.success(original.copy(status = "PLANNED", assignees = listOf("alice"), listId = "a"))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateTodo("1", UpdateTodoRequest(status = "PLANNED", assignees = listOf("alice")), targetListId = "a")
        advanceUntilIdle()

        coVerify {
            repository.updateTodo("1", UpdateTodoRequest(status = "PLANNED", assignees = listOf("alice"), listId = "a"))
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
            Result.success(original.copy(status = "PLANNED", assignees = listOf("alice"), listId = "a"))

        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(INBOX_TAB_ID)

        vm.updateTodo("1", UpdateTodoRequest(status = "PLANNED", assignees = listOf("alice")), targetListId = "a")
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

    // --- Cross-list "smart" views (#256/#263): Alle · Heute · Morgen · Erledigt ---
    // Dates are relative to LocalDate.now() so the predicates bucket the same way every run.

    private val todayIso = java.time.LocalDate.now().toString()
    private val tomorrowIso = java.time.LocalDate.now().plusDays(1).toString()
    private val overdueIso = java.time.LocalDate.now().minusDays(3).toString()
    private val farIso = java.time.LocalDate.now().plusDays(10).toString()
    // done timestamps, device-zone local day → ISO instant
    private fun doneInstant(daysAgo: Long): String =
        java.time.LocalDate.now().minusDays(daysAgo).atTime(9, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toString()

    /** A spread of todos across lists exercising every smart bucket. */
    private fun smartTodos() = listOf(
        todo(id = "today1", status = "PLANNED", listId = "a", dueDate = todayIso),
        todo(id = "today2", status = "PLANNED", listId = "b", dueDate = todayIso),
        todo(id = "tomo", status = "PLANNED", listId = "a", dueDate = tomorrowIso),
        todo(id = "over", status = "PLANNED", listId = "b", dueDate = overdueIso),
        todo(id = "far", status = "PLANNED", listId = "a", dueDate = farIso),
        todo(id = "inbox", status = "INBOX"),
        todo(id = "doneToday", status = "DONE", listId = "b", doneAt = doneInstant(0)),
        todo(id = "doneOld", status = "DONE", listId = "a", doneAt = doneInstant((DONE_WINDOW_DAYS + 5).toLong())),
    )

    private fun smartVm(): TodoViewModel {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a"), list("b")))
        coEvery { repository.getTodos() } returns Result.success(smartTodos())
        return createVm()
    }

    @Test
    fun `smart tab counts mirror the dashboard tiles`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()
        val s = vm.uiState.value

        assertEquals(1, s.inboxCount) // status INBOX
        assertEquals(6, s.allOpenCount) // every non-DONE todo (2 today + tomo + over + far + inbox)
        assertEquals(2, s.todayCount) // today1, today2
        assertEquals(1, s.tomorrowCount) // tomo
        assertEquals(1, s.doneTodayCount) // doneToday (doneOld is outside today)
    }

    @Test
    fun `Alle tab spans every list including done and inbox`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()
        vm.selectList(ALL_TAB_ID)

        val s = vm.uiState.value
        assertEquals(TodosFocus.ALL, s.smartTab)
        assertTrue(s.crossListActive)
        assertNull(s.activeList)
        // every todo is visible (open + done, both lists + inbox)
        assertEquals(smartTodos().map { it.id }.toSet(), s.visibleTodos.map { it.id }.toSet())
    }

    @Test
    fun `Heute tab lists only today's open todos across lists`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()
        vm.selectList(TODAY_TAB_ID)

        val s = vm.uiState.value
        assertEquals(TodosFocus.TODAY, s.smartTab)
        assertEquals(setOf("today1", "today2"), s.visibleTodos.map { it.id }.toSet())
    }

    @Test
    fun `Morgen tab lists only tomorrow's open todos`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()
        vm.selectList(TOMORROW_TAB_ID)

        assertEquals(listOf("tomo"), vm.uiState.value.visibleTodos.map { it.id })
    }

    @Test
    fun `Erledigt tab shows done within the window but not older done`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()
        vm.selectList(DONE_TAB_ID)

        val s = vm.uiState.value
        assertEquals(TodosFocus.DONE, s.smartTab)
        // doneToday is in-window; doneOld (DONE_WINDOW_DAYS+5 ago) is excluded
        assertEquals(listOf("doneToday"), s.visibleTodos.map { it.id })
    }

    @Test
    fun `Erledigt tab includes a done todo from inside the window but before today`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(
            listOf(
                todo(id = "recent", status = "DONE", listId = "a", doneAt = doneInstant(2)), // 2 days ago, in-window
                todo(id = "old", status = "DONE", listId = "a", doneAt = doneInstant((DONE_WINDOW_DAYS + 1).toLong())),
            ),
        )
        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(DONE_TAB_ID)

        val s = vm.uiState.value
        assertEquals(listOf("recent"), s.visibleTodos.map { it.id })
        assertEquals(0, s.doneTodayCount) // the tab/tile COUNT stays "today" only (#263)
    }

    @Test
    fun `applyFocus selects the matching smart tab`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()

        vm.applyFocus(TodosFocus.TOMORROW)
        assertEquals(TodosFocus.TOMORROW, vm.uiState.value.smartTab)

        vm.applyFocus(TodosFocus.INBOX)
        assertTrue(vm.uiState.value.inboxActive)
        assertNull(vm.uiState.value.smartTab)
    }

    @Test
    fun `a smart tab stays active even when there are no lists`() = runTest {
        // No lists → Inbox is normally the default, but an explicit smart tab must win (deep-link).
        coEvery { repository.getTodos() } returns Result.success(
            listOf(todo(id = "t", status = "PLANNED", dueDate = todayIso)),
        )
        val vm = createVm()
        advanceUntilIdle()
        vm.selectList(TODAY_TAB_ID)

        val s = vm.uiState.value
        assertFalse(s.inboxActive)
        assertEquals(TodosFocus.TODAY, s.smartTab)
        assertEquals(listOf("t"), s.visibleTodos.map { it.id })
    }
}
