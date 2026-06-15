package com.homebase.android

import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.data.model.ShoppingTemplateItemDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.repository.ShoppingRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.homebase.android.data.shopping.PendingCheck
import com.homebase.android.data.shopping.ShoppingClock
import com.homebase.android.data.shopping.ShoppingPendingStore
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.ui.shopping.ShoppingViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ShoppingRepository
    private val wsEvents = MutableSharedFlow<ShoppingWebSocketClient.WsEvent>()

    /** Captures the WS "(re)connected" callback the VM registers, so a test can fire it like a reconnect (#269). */
    private val onConnectedSlot = slot<() -> Unit>()
    private fun fireWsReconnect() = onConnectedSlot.captured.invoke()

    /** In-memory [ShoppingPendingStore] standing in for the SharedPreferences-backed one. */
    private class FakeStore(var data: MutableMap<String, PendingCheck> = mutableMapOf()) : ShoppingPendingStore {
        override suspend fun load(): Map<String, PendingCheck> = data.toMap()
        override suspend fun save(pending: Map<String, PendingCheck>) { data = pending.toMutableMap() }
    }

    private lateinit var store: FakeStore
    // Monotonic clock so each toggle gets a distinct `at`.
    private var nowMs = 1_000L
    private val clock = ShoppingClock { nowMs }

    private fun item(
        id: String = "1",
        name: String = "Milch",
        checked: Boolean = false,
    ) = ShoppingItemDto(
        id = id, name = name, checked = checked,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    private fun list(id: String = "L1", name: String = "Liste") = ShoppingListDto(
        id = id, name = name, createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        store = FakeStore()
        nowMs = 1_000L
        every { repository.incomingEvents } returns wsEvents
        // Capture the reconnect callback the VM registers (#269) so tests can fire it.
        every { repository.setWebSocketOnConnected(capture(onConnectedSlot)) } returns Unit
        // load() fetches both lists and items; default lists to empty unless a test overrides.
        coEvery { repository.getLists() } returns Result.success(emptyList())
        // init also loads templates (#215) — default to empty so existing tests are unaffected.
        coEvery { repository.getTemplates() } returns Result.success(emptyList())
    }

    // Owns each VM; clearing the store runs onCleared() → cancels viewModelScope (the backstop loop
    // and any parked in-flight flush). Cleared inside the test body (see [vmTest]) before runTest's
    // implicit final advanceUntilIdle, which would otherwise spin on those long-lived coroutines.
    private val vmStore = ViewModelStore()

    @After
    fun tearDown() {
        vmStore.clear()
        Dispatchers.resetMain()
    }

    /** runTest that always cancels the VM's coroutines before the implicit end-of-test drain. */
    private fun vmTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            vmStore.clear()
        }
    }

    private fun createVm(
        networkAvailable: kotlinx.coroutines.flow.Flow<Unit> = emptyFlow(),
    ): ShoppingViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShoppingViewModel(
                repository = repository,
                token = "test-token",
                pendingStore = store,
                networkAvailable = networkAvailable,
                clock = clock,
                // Large interval so the backstop loop never fires inside advanceUntilIdle().
                flushIntervalMs = 10_000_000L,
            ) as T
        }
        return ViewModelProvider(vmStore, factory)[ShoppingViewModel::class.java]
    }

    @Test
    fun `initial load populates items`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(listOf(item()))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("Milch", vm.uiState.value.items[0].name)
    }

    @Test
    fun `initial load failure sets error`() = vmTest {
        coEvery { repository.getItems() } returns Result.failure(RuntimeException("Network error"))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Network error", vm.uiState.value.error)
    }

    @Test
    fun `addItem prepends new item`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        val created = item(id = "2", name = "Brot")
        coEvery { repository.createItem("Brot", "L1") } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("Brot", vm.uiState.value.items[0].name)
    }

    @Test
    fun `addItem files into the active list`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        coEvery { repository.createItem("Brot", "L1") } returns Result.success(item(id = "2", name = "Brot"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot")
        advanceUntilIdle()

        coVerify { repository.createItem("Brot", "L1") }
    }

    // --- Lists-first: no list-less items (#181) --------------------------------------------

    @Test
    fun `addItem with no lists auto-creates a default list and files the item into it`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())
        val defaultList = list(id = "new-list", name = "Einkaufsliste")
        coEvery { repository.createList("Einkaufsliste") } returns Result.success(defaultList)
        coEvery { repository.createItem("Brot", "new-list") } returns Result.success(
            item(id = "2", name = "Brot").copy(listId = "new-list"),
        )

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot")
        advanceUntilIdle()

        // A default list was created and the item is filed under it — never list-less.
        coVerify { repository.createList("Einkaufsliste") }
        coVerify { repository.createItem("Brot", "new-list") }
        coVerify(exactly = 0) { repository.createItem(any(), null) }
        assertEquals("new-list", vm.uiState.value.activeListId)
        assertEquals(1, vm.uiState.value.lists.size)
        assertEquals("new-list", vm.uiState.value.items.single().listId)
    }

    @Test
    fun `addItem with no lists does not create a list-less item when list creation fails`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())
        coEvery { repository.createList("Einkaufsliste") } returns Result.failure(RuntimeException("boom"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot")
        advanceUntilIdle()

        // List creation failed → the add is skipped rather than producing a list-less item.
        coVerify(exactly = 0) { repository.createItem(any(), any()) }
        assertTrue(vm.uiState.value.items.isEmpty())
        assertEquals("boom", vm.uiState.value.error)
    }

    @Test
    fun `two concurrent adds on a fresh state create exactly one default list and share it`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())

        // Gated createList: every invocation parks on `gate`, and each would mint a DISTINCT list id.
        // Without serialization both adds pass the "no list yet" check and call createList → two
        // lists. The ensureListMutex must collapse this to a single create that both adds reuse.
        val gate = CompletableDeferred<Unit>()
        var createListCalls = 0
        coEvery { repository.createList("Einkaufsliste") } coAnswers {
            val n = ++createListCalls
            gate.await()
            Result.success(list(id = "new-list-$n", name = "Einkaufsliste"))
        }
        coEvery { repository.createItem(any(), any()) } coAnswers {
            val name = firstArg<String>()
            val listId = secondArg<String>()
            Result.success(item(id = "item-$name", name = name).copy(listId = listId))
        }

        val vm = createVm()
        advanceUntilIdle()

        // Two quick submits before either create completes (Enter-Enter; the field isn't disabled).
        vm.addItem("A")
        vm.addItem("B")
        runCurrent() // both coroutines start; the first parks in createList, the second on the mutex

        gate.complete(Unit) // release the (single) in-flight create
        advanceUntilIdle()

        // Exactly one default list ever created; the second add reused it.
        coVerify(exactly = 1) { repository.createList("Einkaufsliste") }
        assertEquals(1, createListCalls)
        assertEquals(1, vm.uiState.value.lists.size)
        // Both items landed on the same single list — nothing split across a duplicate tab.
        assertEquals(2, vm.uiState.value.items.size)
        val listIds = vm.uiState.value.items.map { it.listId }.toSet()
        assertEquals(setOf("new-list-1"), listIds)
        coVerify(exactly = 0) { repository.createItem(any(), null) }
    }

    // --- Named createList double-submit guard (#191) ---------------------------------------

    @Test
    fun `two concurrent createList calls create exactly one list (single-flight double-tap guard)`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())

        // Gated createList: the first call parks on `gate`; a second concurrent call (the double-tap)
        // must be ignored by the single-flight guard rather than firing a second create → two lists.
        // Distinct ids per call so a second create would be observable as a second list.
        val gate = CompletableDeferred<Unit>()
        var createListCalls = 0
        coEvery { repository.createList("Drogerie") } coAnswers {
            val n = ++createListCalls
            gate.await()
            Result.success(list(id = "list-$n", name = "Drogerie"))
        }

        val vm = createVm()
        advanceUntilIdle()

        // Double-tap "Erstellen" before the first create completes (the sheet isn't dismissed yet).
        vm.createList("Drogerie")
        vm.createList("Drogerie")
        runCurrent() // the first call parks in createList; the second is dropped by the guard

        gate.complete(Unit) // release the single in-flight create
        advanceUntilIdle()

        // Exactly one create reached the repository; only one list exists.
        coVerify(exactly = 1) { repository.createList("Drogerie") }
        assertEquals(1, createListCalls)
        assertEquals(1, vm.uiState.value.lists.size)
        assertEquals("list-1", vm.uiState.value.activeListId)
    }

    @Test
    fun `a sequential second createList still creates a second list (deliberate second list)`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())
        // Two separate, deliberate creates of a same-named list: both must land (the guard collapses
        // only a concurrent burst, never a later separate user action). Distinct ids per call.
        var createListCalls = 0
        coEvery { repository.createList("Drogerie") } coAnswers {
            Result.success(list(id = "list-${++createListCalls}", name = "Drogerie"))
        }

        val vm = createVm()
        advanceUntilIdle()

        vm.createList("Drogerie")
        advanceUntilIdle() // first create fully resolves → guard clears
        vm.createList("Drogerie")
        advanceUntilIdle()

        // Both deliberate creates went through → two lists, even with the same name.
        coVerify(exactly = 2) { repository.createList("Drogerie") }
        assertEquals(2, createListCalls)
        assertEquals(2, vm.uiState.value.lists.size)
        assertEquals("list-2", vm.uiState.value.activeListId)
    }

    @Test
    fun `createList with blank name does nothing`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.createList("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createList(any()) }
        assertTrue(vm.uiState.value.lists.isEmpty())
    }

    @Test
    fun `addIngredients with no list auto-creates the default list instead of a list-less batch`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())
        val defaultList = list(id = "new-list", name = "Einkaufsliste")
        coEvery { repository.createList("Einkaufsliste") } returns Result.success(defaultList)
        coEvery { repository.batchAdd("new-list", any()) } returns Result.success(
            BatchAddShoppingResponse(
                added = 1, merged = 0, skipped = 0,
                items = listOf(item(id = "10", name = "200 g Mehl").copy(listId = "new-list")),
            ),
        )

        val vm = createVm()
        advanceUntilIdle()

        var addedSeen = -1
        // No explicit listId and no active list → must route through ensureDefaultList, never null.
        vm.addIngredients(null, listOf(ShoppingLineInput("Mehl", 200.0, "g"))) { a, _ -> addedSeen = a }
        advanceUntilIdle()

        coVerify { repository.createList("Einkaufsliste") }
        coVerify { repository.batchAdd("new-list", any()) }
        coVerify(exactly = 0) { repository.batchAdd(null, any()) }
        assertEquals(1, addedSeen)
        assertEquals("new-list", vm.uiState.value.items.single().listId)
    }

    @Test
    fun `addIngredients with no list skips the batch when list creation fails`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(emptyList())
        coEvery { repository.createList("Einkaufsliste") } returns Result.failure(RuntimeException("boom"))

        val vm = createVm()
        advanceUntilIdle()

        var addedSeen = -1
        var mergedSeen = -1
        vm.addIngredients(null, listOf(ShoppingLineInput("Mehl", 200.0, "g"))) { a, m ->
            addedSeen = a; mergedSeen = m
        }
        advanceUntilIdle()

        // List creation failed → no batch-add with a null list; the callback reports nothing added.
        coVerify(exactly = 0) { repository.batchAdd(any(), any()) }
        assertEquals(0, addedSeen)
        assertEquals(0, mergedSeen)
        assertEquals("boom", vm.uiState.value.error)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `pre-existing list-less items are migrated into the first list on load`() = vmTest {
        val orphan = item(id = "o1", name = "Altlast").copy(listId = null)
        coEvery { repository.getItems() } returns Result.success(listOf(orphan))
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        coEvery { repository.updateItem("o1", UpdateShoppingItemRequest(listId = "L1")) } returns
            Result.success(orphan.copy(listId = "L1"))

        val vm = createVm()
        advanceUntilIdle()

        coVerify { repository.updateItem("o1", UpdateShoppingItemRequest(listId = "L1")) }
        assertEquals("L1", vm.uiState.value.items.single().listId)
    }

    @Test
    fun `a failed migration leaves the orphan list-less but still visible on the first list`() = vmTest {
        val orphan = item(id = "o1", name = "Altlast").copy(listId = null)
        coEvery { repository.getItems() } returns Result.success(listOf(orphan))
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        coEvery { repository.updateItem("o1", UpdateShoppingItemRequest(listId = "L1")) } returns
            Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        advanceUntilIdle()

        // Best-effort: the PUT failed, the row stays list-less, no blocking error surfaced…
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.items.single().listId)
        // …but the safety net keeps it reachable on the first (active) list.
        assertEquals(1, vm.uiState.value.visibleItems.size)
        assertEquals("o1", vm.uiState.value.visibleItems.single().id)
    }

    @Test
    fun `no migration PUT when there are no list-less items`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "1").copy(listId = "L1")))
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))

        val vm = createVm()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateItem(any(), match { it.listId != null }) }
    }

    @Test
    fun `addItem with blank name does nothing`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createItem(any(), any()) }
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `toggleChecked replaces item with updated copy`() = vmTest {
        val original = item(id = "1", checked = false)
        val updated = original.copy(checked = true, checkedAt = "2026-01-02T00:00:00Z")
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        coEvery { repository.updateItem("1", UpdateShoppingItemRequest(checked = true)) } returns Result.success(updated)

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.items[0].checked)
    }

    @Test
    fun `deleteItem removes it from list`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "1")))
        coEvery { repository.deleteItem("1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteItem("1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `clearError removes error from state`() = vmTest {
        coEvery { repository.getItems() } returns Result.failure(RuntimeException("oops"))

        val vm = createVm()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `addIngredients upserts returned items and reports counts`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        val created = item(id = "10", name = "200 g Mehl")
        coEvery { repository.batchAdd(any(), any()) } returns Result.success(
            BatchAddShoppingResponse(added = 1, merged = 0, skipped = 0, items = listOf(created)),
        )

        val vm = createVm()
        advanceUntilIdle()

        var addedSeen = -1
        var mergedSeen = -1
        vm.addIngredients("list-1", listOf(ShoppingLineInput("Mehl", 200.0, "g"))) { a, m ->
            addedSeen = a; mergedSeen = m
        }
        advanceUntilIdle()

        assertEquals(1, addedSeen)
        assertEquals(0, mergedSeen)
        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("200 g Mehl", vm.uiState.value.items[0].name)
    }

    @Test
    fun `addIngredients with empty lines skips the request`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        var reported = false
        vm.addIngredients("list-1", emptyList()) { _, _ -> reported = true }
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.batchAdd(any(), any()) }
        assertTrue(reported)
    }

    @Test
    fun `WS ItemCreated adds item without duplicate`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        val incoming = item(id = "ws-1", name = "Eier")
        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemCreated(incoming))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("ws-1", vm.uiState.value.items[0].id)
    }

    @Test
    fun `WS ItemCreated does not add duplicate`() = vmTest {
        val existing = item(id = "1")
        coEvery { repository.getItems() } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemCreated(existing))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
    }

    @Test
    fun `WS ItemUpdated updates item in place`() = vmTest {
        val original = item(id = "1", name = "Alt")
        coEvery { repository.getItems() } returns Result.success(listOf(original))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemUpdated(original.copy(name = "Neu")))
        advanceUntilIdle()

        assertEquals("Neu", vm.uiState.value.items[0].name)
    }

    @Test
    fun `WS ItemDeleted removes item`() = vmTest {
        val existing = item(id = "1")
        coEvery { repository.getItems() } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemDeleted(existing))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.items.isEmpty())
    }

    // --- Offline-resilient check-off (issue #170) ------------------------------------------

    @Test
    fun `toggleChecked updates optimistically before the network responds`() = vmTest {
        val original = item(id = "1", checked = false)
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        // updateItem never completes within this test → the optimistic state must already show.
        coEvery { repository.updateItem(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)
        runCurrent() // run the synchronous optimistic update; the PUT stays in flight

        assertTrue("checked flips immediately", vm.uiState.value.items[0].checked)
        assertNotNull("checkedAt set locally for cart ordering", vm.uiState.value.items[0].checkedAt)
        assertTrue("item shows the not-synced marker", vm.uiState.value.isPending("1"))
    }

    @Test
    fun `pending check is persisted to the durable store`() = vmTest {
        val original = item(id = "1", checked = false)
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        coEvery { repository.updateItem(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)
        runCurrent()

        assertEquals(true, store.data["1"]?.checked)
    }

    @Test
    fun `successful flush clears the pending marker and persisted entry`() = vmTest {
        val original = item(id = "1", checked = false)
        val updated = original.copy(checked = true, checkedAt = "2026-01-02T00:00:00Z")
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        coEvery { repository.updateItem("1", UpdateShoppingItemRequest(checked = true)) } returns Result.success(updated)

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)
        advanceUntilIdle() // flush runs and lands

        assertFalse(vm.uiState.value.isPending("1"))
        assertTrue(store.data.isEmpty())
        assertTrue(vm.uiState.value.items[0].checked)
    }

    @Test
    fun `offline check stays optimistic, pending and queued`() = vmTest {
        val original = item(id = "1", checked = false)
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        coEvery { repository.updateItem("1", any()) } returns Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)
        runCurrent() // optimistic update + one failed flush attempt; do NOT advance the backstop timer

        assertTrue("stays optimistically checked", vm.uiState.value.items[0].checked)
        assertTrue("still marked not-synced", vm.uiState.value.isPending("1"))
        assertEquals("intent survives in the durable store", true, store.data["1"]?.checked)
        assertNull("offline failure is not surfaced as a blocking error", vm.uiState.value.error)
    }

    @Test
    fun `queue restored from the store on init shows as pending`() = vmTest {
        store.data = mutableMapOf("1" to PendingCheck(checked = true, at = 500L))
        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "1", checked = true)))
        // keep the restored entry unsent so it stays visible
        coEvery { repository.updateItem(any(), any()) } returns Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        runCurrent()

        assertTrue("restored entry is marked pending from the first frame", vm.uiState.value.isPending("1"))
    }

    @Test
    fun `WS ItemDeleted discards a pending check for that item`() = vmTest {
        val original = item(id = "1", checked = false)
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        coEvery { repository.updateItem("1", any()) } returns Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)
        runCurrent()
        assertTrue(vm.uiState.value.isPending("1"))

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemDeleted(original))
        runCurrent()

        assertFalse("pending entry dropped on delete", vm.uiState.value.isPending("1"))
        assertTrue("durable store no longer holds it", store.data.isEmpty())
    }

    @Test
    fun `unchecking clears the local checkedAt`() = vmTest {
        val checkedItem = item(id = "1", checked = true).copy(checkedAt = "2026-01-01T10:00:00Z")
        coEvery { repository.getItems() } returns Result.success(listOf(checkedItem))
        coEvery { repository.updateItem("1", any()) } returns Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(checkedItem)
        runCurrent()

        assertFalse(vm.uiState.value.items[0].checked)
        assertNull(vm.uiState.value.items[0].checkedAt)
    }

    @Test
    fun `re-toggle while in flight keeps the newer intent`() = vmTest {
        val original = item(id = "1", checked = false)
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        // First flush attempt (checked=true) fails offline; the user re-toggles to false meanwhile.
        coEvery { repository.updateItem("1", any()) } returns Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleChecked(original)               // → true, at=1000
        runCurrent()
        nowMs = 2_000L
        vm.toggleChecked(original.copy(checked = true)) // → false, at=2000
        runCurrent()

        assertFalse("optimistic state reflects the newer toggle", vm.uiState.value.items[0].checked)
        assertEquals("queue holds the newer intent", false, store.data["1"]?.checked)
        assertEquals(2_000L, store.data["1"]?.at)
    }

    @Test
    fun `a retry that succeeds on a later trigger clears the pending entry and marker`() = vmTest {
        val original = item(id = "1", checked = false)
        val updated = original.copy(checked = true, checkedAt = "2026-01-02T00:00:00Z")
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        // First flush fails offline; a later trigger (networkAvailable) retries and lands.
        var online = false
        coEvery { repository.updateItem("1", UpdateShoppingItemRequest(checked = true)) } coAnswers {
            if (online) Result.success(updated) else Result.failure(java.io.IOException("offline"))
        }
        val network = MutableSharedFlow<Unit>()

        val vm = createVm(networkAvailable = network)
        advanceUntilIdle()

        vm.toggleChecked(original)
        // runCurrent (not advanceUntilIdle): the queue stays non-empty while offline, so advancing
        // virtual time would fire the (armed) backstop loop forever. We only need the immediate
        // optimistic update + the first failed flush attempt here.
        runCurrent()
        assertTrue("still pending after the offline attempt", vm.uiState.value.isPending("1"))
        assertEquals("intent still queued", true, store.data["1"]?.checked)

        // Connectivity returns → the network-available trigger flushes again, now succeeding. The
        // queue drains here, so advanceUntilIdle is safe (the backstop loop exits once empty).
        online = true
        network.emit(Unit)
        advanceUntilIdle()

        assertFalse("pending marker gone after the retry lands", vm.uiState.value.isPending("1"))
        assertTrue("durable store cleared after the retry lands", store.data.isEmpty())
        assertTrue("item reflects the server's checked state", vm.uiState.value.items[0].checked)
    }

    @Test
    fun `a pending entry restored on init is re-PUT and cleared`() = vmTest {
        // Previous session left a queued check on disk; this session must actually re-send it.
        store.data = mutableMapOf("1" to PendingCheck(checked = true, at = 500L))
        val restoredItem = item(id = "1", checked = true).copy(checkedAt = "2026-01-01T09:00:00Z")
        coEvery { repository.getItems() } returns Result.success(listOf(restoredItem))
        coEvery { repository.updateItem("1", UpdateShoppingItemRequest(checked = true)) } returns
            Result.success(restoredItem)

        val vm = createVm()
        advanceUntilIdle() // restore → flush re-PUTs the entry → lands

        coVerify(exactly = 1) { repository.updateItem("1", UpdateShoppingItemRequest(checked = true)) }
        assertFalse("restored entry no longer pending once re-sent", vm.uiState.value.isPending("1"))
        assertTrue("durable store cleared after the restored entry lands", store.data.isEmpty())
    }

    @Test
    fun `a delete during an in-flight flush PUT does not resurrect the item`() = vmTest {
        val original = item(id = "1", checked = false)
        val staleEcho = original.copy(checked = true, checkedAt = "2026-01-02T00:00:00Z")
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        coEvery { repository.deleteItem("1") } returns Result.success(Unit)
        // The flush PUT parks on this gate; the test deletes the item, then releases it. The PUT then
        // "succeeds" with a now-stale echo that must NOT re-add the deleted row.
        val putGate = CompletableDeferred<Unit>()
        coEvery { repository.updateItem("1", UpdateShoppingItemRequest(checked = true)) } coAnswers {
            putGate.await()
            Result.success(staleEcho)
        }

        val vm = createVm()
        advanceUntilIdle()

        // runCurrent throughout: while the PUT is parked the queue stays non-empty, so advancing
        // virtual time would spin the armed backstop loop. runCurrent processes the immediately
        // ready continuations without firing the (far-future) backstop delay.
        vm.toggleChecked(original) // enqueue + flush; the PUT now parks on putGate
        runCurrent()
        assertTrue("the check-off is in flight", vm.uiState.value.isPending("1"))

        vm.deleteItem("1") // delete arrives while the PUT is suspended: removes row + dequeues
        runCurrent()
        assertTrue("item gone after delete", vm.uiState.value.items.none { it.id == "1" })

        putGate.complete(Unit) // the parked PUT resumes and returns its stale success
        runCurrent()

        assertTrue("stale PUT success must not resurrect the deleted item", vm.uiState.value.items.none { it.id == "1" })
        assertFalse("no lingering pending marker", vm.uiState.value.isPending("1"))
        assertTrue("durable store is empty", store.data.isEmpty())
    }

    // --- Templates (named standard lists, #215) ------------------------------------------------

    private fun template(
        id: String = "t1",
        name: String = "Wocheneinkauf",
        items: List<String> = listOf("Milch", "Brot", "Eier"),
    ) = ShoppingTemplateDto(
        id = id, name = name,
        items = items.mapIndexed { i, n -> ShoppingTemplateItemDto(id = "$id-$i", name = n, sortOrder = i) },
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun `init loads templates into state`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getTemplates() } returns Result.success(listOf(template()))

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.templates.size)
        assertEquals(listOf("Milch", "Brot", "Eier"), vm.uiState.value.templates[0].items.map { it.name })
    }

    @Test
    fun `applyTemplate batch-adds the selected names to the active list`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        val lines = slot<List<ShoppingLineInput>>()
        coEvery { repository.batchAdd(eq("L1"), capture(lines)) } returns Result.success(
            BatchAddShoppingResponse(added = 2, merged = 0, skipped = 0, items = emptyList()),
        )

        val vm = createVm()
        advanceUntilIdle()

        var addedSeen = -1
        // User unticked "Brot": only the two chosen names are applied.
        vm.applyTemplate(listOf("Milch", "Eier")) { a, _ -> addedSeen = a }
        advanceUntilIdle()

        assertEquals(2, addedSeen)
        coVerify { repository.batchAdd("L1", any()) }
        // Names only, no amount/unit (templates carry just names).
        assertEquals(listOf("Milch", "Eier"), lines.captured.map { it.name })
        assertTrue(lines.captured.all { it.amount == null && it.unit == null })
    }

    @Test
    fun `createTemplate upserts the returned template`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.createTemplate("Drogerie", listOf("Zahnpasta")) } returns
            Result.success(template(id = "t9", name = "Drogerie", items = listOf("Zahnpasta")))

        val vm = createVm()
        advanceUntilIdle()

        var done = false
        vm.createTemplate("Drogerie", listOf("Zahnpasta")) { done = true }
        advanceUntilIdle()

        assertTrue(done)
        coVerify { repository.createTemplate("Drogerie", listOf("Zahnpasta")) }
        assertEquals(1, vm.uiState.value.templates.size)
        assertEquals("Drogerie", vm.uiState.value.templates[0].name)
    }

    @Test
    fun `createTemplate with a blank name no-ops`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.createTemplate("   ", listOf("X"))
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createTemplate(any(), any()) }
    }

    @Test
    fun `updateTemplate replaces the template in state`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getTemplates() } returns Result.success(listOf(template(id = "t1", name = "Alt")))
        coEvery { repository.updateTemplate("t1", "Neu", listOf("Salz")) } returns
            Result.success(template(id = "t1", name = "Neu", items = listOf("Salz")))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateTemplate("t1", "Neu", listOf("Salz"))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.templates.size)
        assertEquals("Neu", vm.uiState.value.templates.first { it.id == "t1" }.name)
        assertEquals(listOf("Salz"), vm.uiState.value.templates.first { it.id == "t1" }.items.map { it.name })
    }

    @Test
    fun `deleteTemplate removes it from state`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.getTemplates() } returns Result.success(listOf(template(id = "t1"), template(id = "t2")))
        coEvery { repository.deleteTemplate("t1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.templates.size)

        vm.deleteTemplate("t1")
        advanceUntilIdle()

        assertEquals(listOf("t2"), vm.uiState.value.templates.map { it.id })
    }

    @Test
    fun `a template WS event refetches the template list`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        // First load empty; after the WS event the refetch returns one template.
        coEvery { repository.getTemplates() } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(template(id = "t1"))),
        )

        val vm = createVm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.templates.isEmpty())

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.TemplateChanged(template(id = "t1")))
        advanceUntilIdle()

        assertEquals(listOf("t1"), vm.uiState.value.templates.map { it.id })
        coVerify(atLeast = 2) { repository.getTemplates() }
    }

    // --- #269: reconnect re-syncs the LIST (not just the offline queue) + pull-to-refresh ---

    @Test
    fun `WS reconnect refetches the item list so a partner's change appears`() = vmTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        // Items carry their list so the list-less migration sweep finds nothing to adopt.
        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "1", name = "Milch").copy(listId = "L1")))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)

        // While our socket was dead the partner added an item; on reconnect we refetch the list.
        coEvery { repository.getItems() } returns Result.success(
            listOf(
                item(id = "1", name = "Milch").copy(listId = "L1"),
                item(id = "2", name = "Brot").copy(listId = "L1"),
            ),
        )
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(setOf("1", "2"), vm.uiState.value.items.map { it.id }.toSet())
    }

    @Test
    fun `WS reconnect re-sync does not clobber a still-pending offline check`() = vmTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        val original = item(id = "1", name = "Milch", checked = false).copy(listId = "L1")
        coEvery { repository.getItems() } returns Result.success(listOf(original))
        // The check-off PUT never lands (offline) so the intent stays queued.
        coEvery { repository.updateItem(eq("1"), any()) } returns Result.failure(java.io.IOException("offline"))

        val vm = createVm()
        runCurrent() // initial load + queue restore; do NOT advance the backstop timer

        vm.toggleChecked(original) // optimistic check + enqueue; PUT fails, stays pending
        runCurrent()
        assertTrue(vm.uiState.value.isPending("1"))
        assertTrue(vm.uiState.value.items.first { it.id == "1" }.checked)

        // Reconnect re-sync: the server still reports it unchecked (our PUT never landed). The merge
        // must let the pending local check win, not revert the checkbox under the user.
        coEvery { repository.getItems() } returns Result.success(listOf(original.copy(checked = false)))
        fireWsReconnect()
        runCurrent()

        assertTrue(vm.uiState.value.items.first { it.id == "1" }.checked)
        assertTrue(vm.uiState.value.isPending("1"))
    }

    @Test
    fun `WS reconnect re-sync keeps existing items on a transient failure`() = vmTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "1", name = "Milch").copy(listId = "L1")))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)

        coEvery { repository.getItems() } returns Result.failure(RuntimeException("down"))
        fireWsReconnect()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `refresh refetches lists and items`() = vmTest {
        coEvery { repository.getLists() } returns Result.success(listOf(list(id = "L1")))
        coEvery { repository.getItems() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "r", name = "Neu").copy(listId = "L1")))
        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.isLoading)
    }
}
