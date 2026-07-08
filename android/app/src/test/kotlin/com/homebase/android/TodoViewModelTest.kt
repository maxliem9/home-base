package com.homebase.android

import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.RecurrenceDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.model.DoneWindowConfigResponse
import com.homebase.android.ui.aufgaben.TodoDraft
import com.homebase.android.ui.aufgaben.TodoSaveStatus
import com.homebase.android.ui.aufgaben.toCreateRequest
import com.homebase.android.ui.aufgaben.toUpdateRequest
import com.homebase.android.data.aufgaben.TodoSnapshot
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.aufgaben.ALL_TAB_ID
import com.homebase.android.ui.aufgaben.DONE_TAB_ID
import com.homebase.android.ui.aufgaben.DONE_WINDOW_DAYS
import com.homebase.android.ui.aufgaben.INBOX_TAB_ID
import com.homebase.android.ui.aufgaben.OVERDUE_TAB_ID
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

    /** In-memory [SnapshotStore] standing in for the SharedPreferences-backed read-cache (#520). */
    private class FakeSnapshotStore(var data: TodoSnapshot? = null) : SnapshotStore<TodoSnapshot> {
        override suspend fun load(): TodoSnapshot? = data
        override suspend fun save(snapshot: TodoSnapshot) { data = snapshot }
    }

    private fun createVm(snapshotStore: SnapshotStore<TodoSnapshot>? = null): TodoViewModel =
        TodoViewModel(repository, configRepository, "test-token", snapshotStore = snapshotStore)

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
        assertEquals(1, s.overdueCount) // over
        assertEquals(2, s.todayCount) // today1, today2
        assertEquals(1, s.tomorrowCount) // tomo
        assertEquals(1, s.doneTodayCount) // doneToday (doneOld is outside today)
    }

    @Test
    fun `Ueberfaellig tab lists only overdue open todos across lists`() = runTest {
        val vm = smartVm()
        advanceUntilIdle()
        vm.selectList(OVERDUE_TAB_ID)

        val s = vm.uiState.value
        assertEquals(TodosFocus.OVERDUE, s.smartTab)
        assertEquals(listOf("over"), s.visibleTodos.map { it.id })
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

    // --- Edit-sheet auto-save -------------------------------------------------------------

    private fun draft(
        title: String = "A",
        description: String = "",
        assignees: List<String> = emptyList(),
        dueDate: String? = null,
        dueTime: String? = null,
        priority: String? = null,
        recurrence: RecurrenceDto? = null,
        targetListId: String? = null,
    ) = TodoDraft(title, description, assignees, dueDate, dueTime, priority, recurrence, targetListId)

    @Test
    fun `toCreateRequest omits blank and empty fields`() {
        val req = draft(title = "  Buy milk  ").toCreateRequest(listId = null)
        assertEquals("Buy milk", req.title)
        assertNull(req.description)
        assertNull(req.assignees)
        assertNull(req.dueDate)
        assertNull(req.recurrence)
    }

    @Test
    fun `toUpdateRequest clears missing fields and derives status`() {
        // A date but no explicit assignees ⇒ PLANNED; no recurrence ⇒ freq NONE; blank desc ⇒ "".
        val planned = draft(title = "T", dueDate = "2026-07-10").toUpdateRequest()
        assertEquals("PLANNED", planned.status)
        assertEquals("2026-07-10", planned.dueDate)
        assertEquals("", planned.description)
        assertEquals("NONE", planned.recurrence?.freq)

        // No assignees, no date ⇒ INBOX; a time is dropped without a date.
        val inbox = draft(title = "T", dueTime = "07:30").toUpdateRequest()
        assertEquals("INBOX", inbox.status)
        assertEquals("", inbox.dueDate)
        assertEquals("", inbox.dueTime)
    }

    @Test
    fun `editing an existing todo auto-saves after the debounce`() = runTest {
        val existing = todo(id = "1", title = "Old", status = "INBOX")
        coEvery { repository.getTodos() } returns Result.success(listOf(existing))
        val slot = slot<UpdateTodoRequest>()
        coEvery { repository.updateTodo(eq("1"), capture(slot)) } answers {
            Result.success(existing.copy(title = slot.captured.title ?: existing.title))
        }
        val vm = createVm()
        advanceUntilIdle()

        vm.openTodoEditor(existing)
        // an unchanged push must NOT save
        vm.updateTodoDraft(draft(title = "Old"), valid = true)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.updateTodo(any(), any()) }

        // a changed title auto-saves once the debounce elapses
        vm.updateTodoDraft(draft(title = "New"), valid = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateTodo("1", any()) }
        assertEquals("New", slot.captured.title)
        assertEquals(TodoSaveStatus.SAVED, vm.todoEditor.value?.status)
    }

    @Test
    fun `editing to a blank title holds the auto-save`() = runTest {
        val existing = todo(id = "1", title = "Old", status = "INBOX")
        coEvery { repository.getTodos() } returns Result.success(listOf(existing))
        val vm = createVm()
        advanceUntilIdle()

        vm.openTodoEditor(existing)
        // an invalid draft (blank title) must never be persisted
        vm.updateTodoDraft(draft(title = "   "), valid = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateTodo(any(), any()) }
    }

    @Test
    fun `opening a todo whose dueTime is HH mm ss does not phantom-save`() = runTest {
        // Legacy value with seconds; the sheet pushes the normalized "HH:mm" for the same time, so the
        // baseline must normalize too (else opening alone would look dirty and auto-save — the fix).
        val existing = todo(id = "1", title = "T", status = "PLANNED", dueDate = "2026-07-10").copy(dueTime = "14:30:00")
        coEvery { repository.getTodos() } returns Result.success(listOf(existing))
        val vm = createVm()
        advanceUntilIdle()

        vm.openTodoEditor(existing)
        vm.updateTodoDraft(draft(title = "T", dueDate = "2026-07-10", dueTime = "14:30"), valid = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateTodo(any(), any()) }
    }

    @Test
    fun `createTodoFromDraft creates the todo with all fields (never auto-created)`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        val slot = slot<CreateTodoRequest>()
        coEvery { repository.createTodo(capture(slot)) } answers {
            Result.success(todo(id = "c1", title = slot.captured.title))
        }
        val vm = createVm()
        advanceUntilIdle()

        // opening the sheet for a new todo does NOT touch the repository — only the explicit commit does
        val result = vm.createTodoFromDraft(
            draft(title = "Fresh", description = "d", dueDate = "2026-07-10", recurrence = RecurrenceDto("WEEKLY", 2)),
        )

        assertTrue(result.isSuccess)
        assertEquals("Fresh", slot.captured.title)
        assertEquals("d", slot.captured.description)
        assertEquals("2026-07-10", slot.captured.dueDate)
        assertEquals("WEEKLY", slot.captured.recurrence?.freq)
        assertEquals(listOf("c1"), vm.uiState.value.todos.map { it.id }) // upserted into the list
    }

    @Test
    fun `closeTodoEditor flushes the last edit without waiting for the debounce`() = runTest {
        val existing = todo(id = "e1", title = "Old", status = "INBOX")
        coEvery { repository.getTodos() } returns Result.success(listOf(existing))
        coEvery { repository.updateTodo(eq("e1"), any()) } returns Result.success(existing.copy(title = "New"))
        val vm = createVm()
        advanceUntilIdle()

        vm.openTodoEditor(existing)
        vm.updateTodoDraft(draft(title = "New"), valid = true)
        // close BEFORE advancing past the debounce window — the flush must still persist the edit
        vm.closeTodoEditor()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateTodo("e1", any()) }
        assertNull(vm.todoEditor.value) // editor cleared after the flush
    }

    @Test
    fun `discardTodoEditor deletes an already-created todo`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.deleteTodo("x1") } returns Result.success(Unit)
        val vm = createVm()
        advanceUntilIdle()

        vm.openTodoEditor(todo(id = "x1"))
        vm.discardTodoEditor("x1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteTodo("x1") }
        assertNull(vm.todoEditor.value)
    }

    // --- Offline read-cache (#520) -------------------------------------------------------------

    @Test
    fun `cold start with no connection seeds the cached lists and todos`() = runTest {
        // The fetch fails (no signal) but a previous session cached the screen.
        coEvery { repository.getLists() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getTodos() } returns Result.failure(java.io.IOException("offline"))
        val cache = FakeSnapshotStore(
            TodoSnapshot(lists = listOf(list("a")), todos = listOf(todo(id = "1", title = "Milch kaufen"))),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        // Shows the old state instead of an empty screen, and does not nag with a blocking error.
        assertEquals(listOf("a"), vm.uiState.value.lists.map { it.id })
        assertEquals(listOf("Milch kaufen"), vm.uiState.value.todos.map { it.title })
        assertFalse(vm.uiState.value.isLoading)
        assertNull("offline refresh over cached data is not surfaced as an error", vm.uiState.value.error)
    }

    @Test
    fun `a successful fetch wins over the cached snapshot`() = runTest {
        // Cache holds a stale todo; the server returns fresh data — the fresh data must win.
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "2", title = "Frisch", listId = "a")))
        val cache = FakeSnapshotStore(TodoSnapshot(todos = listOf(todo(id = "1", title = "STALE", listId = "a"))))

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Frisch"), vm.uiState.value.todos.map { it.title })
    }

    @Test
    fun `a successful load is mirrored into the cache`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(listOf(todo(id = "1", title = "Milch", listId = "a")))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Milch"), cache.data?.todos?.map { it.title })
        assertEquals(listOf("a"), cache.data?.lists?.map { it.id })
    }

    @Test
    fun `an optimistic add is mirrored into the cache`() = runTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list("a")))
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.createTodo(any()) } returns Result.success(todo(id = "9", title = "Brot", listId = "a"))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        vm.addTodo("Brot")
        advanceUntilIdle()

        assertEquals(listOf("Brot"), cache.data?.todos?.map { it.title })
    }

    @Test
    fun `an offline cold start does not overwrite the cache with an empty snapshot`() = runTest {
        coEvery { repository.getLists() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getTodos() } returns Result.failure(java.io.IOException("offline"))
        val cached = TodoSnapshot(lists = listOf(list("a")), todos = listOf(todo(id = "1")))
        val cache = FakeSnapshotStore(cached)

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals("cache survives an offline launch", cached.todos.map { it.id }, cache.data?.todos?.map { it.id })
        assertEquals(cached.lists.map { it.id }, cache.data?.lists?.map { it.id })
    }
}
