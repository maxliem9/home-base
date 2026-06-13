package com.homebase.android

import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        store = FakeStore()
        nowMs = 1_000L
        every { repository.incomingEvents } returns wsEvents
        // load() fetches both lists and items; default lists to empty unless a test overrides.
        coEvery { repository.getLists() } returns Result.success(emptyList())
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
        val created = item(id = "2", name = "Brot")
        coEvery { repository.createItem("Brot", null) } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("Brot", vm.uiState.value.items[0].name)
    }

    @Test
    fun `addItem uses null list when none is selected`() = vmTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.createItem("Brot", null) } returns Result.success(item(id = "2", name = "Brot"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot")
        advanceUntilIdle()

        coVerify { repository.createItem("Brot", null) }
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
}
