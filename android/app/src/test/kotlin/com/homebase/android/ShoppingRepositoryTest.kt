package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateShoppingItemRequest
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.repository.GENERIC_ERROR_TEXT
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
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
class ShoppingRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var wsClient: ShoppingWebSocketClient
    private lateinit var repository: ShoppingRepository

    private fun item(id: String = "1", name: String = "Milch") = ShoppingItemDto(
        id = id, name = name, checked = false,
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        api = mockk()
        wsClient = mockk(relaxed = true)
        every { wsClient.events } returns emptyFlow()
        repository = ShoppingRepository(api, wsClient)
    }

    @Test
    fun `getItems returns api result on success`() = runTest {
        val items = listOf(item("1"), item("2"))
        coEvery { api.getShoppingItems() } returns items

        val result = repository.getItems()

        assertTrue(result.isSuccess)
        assertEquals(items, result.getOrNull())
    }

    @Test
    fun `getItems maps unknown errors to the German fallback text`() = runTest {
        coEvery { api.getShoppingItems() } throws RuntimeException("Network error")

        val result = repository.getItems()

        assertTrue(result.isFailure)
        assertEquals(GENERIC_ERROR_TEXT, result.exceptionOrNull()?.message)
    }

    @Test
    fun `createItem delegates to api with name and category`() = runTest {
        val expected = item(name = "Äpfel")
        coEvery { api.createShoppingItem(CreateShoppingItemRequest("Äpfel", "Obst")) } returns expected

        val result = repository.createItem("Äpfel", "Obst")

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify { api.createShoppingItem(CreateShoppingItemRequest("Äpfel", "Obst")) }
    }

    @Test
    fun `updateItem delegates to api`() = runTest {
        val updated = item().copy(checked = true)
        val request = UpdateShoppingItemRequest(checked = true)
        coEvery { api.updateShoppingItem("1", request) } returns updated

        val result = repository.updateItem("1", request)

        assertTrue(result.isSuccess)
        assertEquals(updated, result.getOrNull())
    }

    @Test
    fun `deleteItem delegates to api`() = runTest {
        coEvery { api.deleteShoppingItem("1") } returns Unit

        val result = repository.deleteItem("1")

        assertTrue(result.isSuccess)
        coVerify { api.deleteShoppingItem("1") }
    }

    @Test
    fun `deleteItem returns failure on api exception`() = runTest {
        coEvery { api.deleteShoppingItem("1") } throws RuntimeException("Not found")

        val result = repository.deleteItem("1")

        assertTrue(result.isFailure)
    }
}
