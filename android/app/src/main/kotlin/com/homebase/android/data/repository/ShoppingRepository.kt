package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.BatchAddShoppingRequest
import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.CreateShoppingItemRequest
import com.homebase.android.data.model.CreateShoppingListRequest
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.model.UpdateShoppingListRequest
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import kotlinx.coroutines.flow.Flow

class ShoppingRepository(
    private val api: HomeBaseApi,
    private val wsClient: ShoppingWebSocketClient,
) {
    val incomingEvents: Flow<ShoppingWebSocketClient.WsEvent> = wsClient.events

    // --- Items ---

    suspend fun getItems(): Result<List<ShoppingItemDto>> = runCatching { api.getShoppingItems() }

    suspend fun createItem(name: String, listId: String?): Result<ShoppingItemDto> =
        runCatching { api.createShoppingItem(CreateShoppingItemRequest(name, listId)) }

    suspend fun batchAdd(listId: String?, lines: List<ShoppingLineInput>): Result<BatchAddShoppingResponse> =
        runCatching { api.batchAddShoppingItems(BatchAddShoppingRequest(listId, lines)) }

    suspend fun updateItem(id: String, request: UpdateShoppingItemRequest): Result<ShoppingItemDto> =
        runCatching { api.updateShoppingItem(id, request) }

    suspend fun deleteItem(id: String): Result<Unit> =
        runCatching { api.deleteShoppingItem(id) }

    // --- Lists ---

    suspend fun getLists(): Result<List<ShoppingListDto>> = runCatching { api.getShoppingLists() }

    suspend fun createList(name: String): Result<ShoppingListDto> =
        runCatching { api.createShoppingList(CreateShoppingListRequest(name)) }

    suspend fun updateList(id: String, request: UpdateShoppingListRequest): Result<ShoppingListDto> =
        runCatching { api.updateShoppingList(id, request) }

    suspend fun deleteList(id: String): Result<Unit> = runCatching { api.deleteShoppingList(id) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun disconnectWebSocket() = wsClient.disconnect()
}
