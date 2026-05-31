package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateShoppingItemRequest
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import kotlinx.coroutines.flow.Flow

class ShoppingRepository(
    private val api: HomeBaseApi,
    private val wsClient: ShoppingWebSocketClient,
) {
    val incomingEvents: Flow<ShoppingWebSocketClient.WsEvent> = wsClient.events

    suspend fun getItems(): Result<List<ShoppingItemDto>> = runCatching { api.getShoppingItems() }

    suspend fun createItem(name: String, category: String?): Result<ShoppingItemDto> =
        runCatching { api.createShoppingItem(CreateShoppingItemRequest(name, category)) }

    suspend fun updateItem(id: String, request: UpdateShoppingItemRequest): Result<ShoppingItemDto> =
        runCatching { api.updateShoppingItem(id, request) }

    suspend fun deleteItem(id: String): Result<Unit> =
        runCatching { api.deleteShoppingItem(id) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun disconnectWebSocket() = wsClient.disconnect()
}
