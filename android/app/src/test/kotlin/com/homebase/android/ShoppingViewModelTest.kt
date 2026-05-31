package com.homebase.android

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.ui.shopping.ShoppingViewModel
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
class ShoppingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ShoppingRepository
    private val wsEvents = MutableSharedFlow<ShoppingWebSocketClient.WsEvent>()

    private fun item(
        id: String = "1",
        name: String = "Milch",
        category: String? = null,
        checked: Boolean = false,
    ) = ShoppingItemDto(
        id = id, name = name, category = category, checked = checked,
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

    private fun createVm() = ShoppingViewModel(repository, "test-token")

    @Test
    fun `initial load populates items`() = runTest {
        coEvery { repository.getItems() } returns Result.success(listOf(item()))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("Milch", vm.uiState.value.items[0].name)
    }

    @Test
    fun `initial load failure sets error`() = runTest {
        coEvery { repository.getItems() } returns Result.failure(RuntimeException("Network error"))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Network error", vm.uiState.value.error)
    }

    @Test
    fun `addItem prepends new item`() = runTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        val created = item(id = "2", name = "Brot")
        coEvery { repository.createItem("Brot", null) } returns Result.success(created)

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot", null)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("Brot", vm.uiState.value.items[0].name)
    }

    @Test
    fun `addItem normalises blank category to null`() = runTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())
        coEvery { repository.createItem("Brot", null) } returns Result.success(item(id = "2", name = "Brot"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("Brot", "   ")
        advanceUntilIdle()

        coVerify { repository.createItem("Brot", null) }
    }

    @Test
    fun `addItem with blank name does nothing`() = runTest {
        coEvery { repository.getItems() } returns Result.success(emptyList())

        val vm = createVm()
        advanceUntilIdle()

        vm.addItem("   ", null)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createItem(any(), any()) }
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `toggleChecked replaces item with updated copy`() = runTest {
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
    fun `deleteItem removes it from list`() = runTest {
        coEvery { repository.getItems() } returns Result.success(listOf(item(id = "1")))
        coEvery { repository.deleteItem("1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteItem("1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `clearError removes error from state`() = runTest {
        coEvery { repository.getItems() } returns Result.failure(RuntimeException("oops"))

        val vm = createVm()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `WS ItemCreated adds item without duplicate`() = runTest {
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
    fun `WS ItemCreated does not add duplicate`() = runTest {
        val existing = item(id = "1")
        coEvery { repository.getItems() } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemCreated(existing))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
    }

    @Test
    fun `WS ItemUpdated updates item in place`() = runTest {
        val original = item(id = "1", name = "Alt")
        coEvery { repository.getItems() } returns Result.success(listOf(original))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemUpdated(original.copy(name = "Neu")))
        advanceUntilIdle()

        assertEquals("Neu", vm.uiState.value.items[0].name)
    }

    @Test
    fun `WS ItemDeleted removes item`() = runTest {
        val existing = item(id = "1")
        coEvery { repository.getItems() } returns Result.success(listOf(existing))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.ItemDeleted(existing))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.items.isEmpty())
    }
}
