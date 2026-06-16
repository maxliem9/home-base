package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.BatchAddShoppingRequest
import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.CreateShoppingItemRequest
import com.homebase.android.data.model.CreateShoppingListRequest
import com.homebase.android.data.model.CreateShoppingTemplateRequest
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.data.model.TemplateItemInput
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.model.UpdateShoppingListRequest
import com.homebase.android.data.model.UpdateShoppingTemplateRequest
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

class ShoppingRepository(
    private val api: HomeBaseApi,
    private val wsClient: ShoppingWebSocketClient,
) {
    val incomingEvents: Flow<ShoppingWebSocketClient.WsEvent> = wsClient.events

    // --- Items ---

    suspend fun getItems(): Result<List<ShoppingItemDto>> = apiCatching { api.getShoppingItems() }

    suspend fun createItem(name: String, listId: String?): Result<ShoppingItemDto> =
        apiCatching { api.createShoppingItem(CreateShoppingItemRequest(name, listId)) }

    suspend fun batchAdd(listId: String?, lines: List<ShoppingLineInput>): Result<BatchAddShoppingResponse> =
        apiCatching { api.batchAddShoppingItems(BatchAddShoppingRequest(listId, lines)) }

    suspend fun updateItem(id: String, request: UpdateShoppingItemRequest): Result<ShoppingItemDto> =
        apiCatching { api.updateShoppingItem(id, request) }

    suspend fun deleteItem(id: String): Result<Unit> =
        apiCatching { api.deleteShoppingItem(id) }

    // --- Lists ---

    suspend fun getLists(): Result<List<ShoppingListDto>> = apiCatching { api.getShoppingLists() }

    suspend fun createList(name: String): Result<ShoppingListDto> =
        apiCatching { api.createShoppingList(CreateShoppingListRequest(name)) }

    suspend fun updateList(id: String, request: UpdateShoppingListRequest): Result<ShoppingListDto> =
        apiCatching { api.updateShoppingList(id, request) }

    suspend fun deleteList(id: String): Result<Unit> = apiCatching { api.deleteShoppingList(id) }

    // --- Templates (named standard lists, #215) ---

    suspend fun getTemplates(): Result<List<ShoppingTemplateDto>> = apiCatching { api.getShoppingTemplates() }

    suspend fun createTemplate(name: String, itemNames: List<String>): Result<ShoppingTemplateDto> =
        apiCatching(mapHttpError = ::germanTemplateError) {
            api.createShoppingTemplate(CreateShoppingTemplateRequest(name.trim(), itemNames.toInputs()))
        }

    /** Rename and replace the item set wholesale (web/recipe parity); both fields always sent. */
    suspend fun updateTemplate(id: String, name: String, itemNames: List<String>): Result<ShoppingTemplateDto> =
        apiCatching(mapHttpError = ::germanTemplateError) {
            api.updateShoppingTemplate(id, UpdateShoppingTemplateRequest(name.trim(), itemNames.toInputs()))
        }

    suspend fun deleteTemplate(id: String): Result<Unit> =
        apiCatching(mapHttpError = ::germanTemplateError) { api.deleteShoppingTemplate(id) }

    /** Drop blank names (a template item is just a name; an empty one is meaningless — backend does the same). */
    private fun List<String>.toInputs(): List<TemplateItemInput> =
        mapNotNull { it.trim().ifBlank { null } }.map { TemplateItemInput(it) }

    /** Map a failed template create/update/delete to German text via its ErrorResponse.code. */
    private fun germanTemplateError(e: HttpException): String = when (errorCodeOf(e)) {
        "INVALID_TEMPLATE" -> "Der Name darf nicht leer sein."
        "NOT_FOUND" -> "Vorlage nicht gefunden – bitte neu laden."
        else -> "Vorlage konnte nicht gespeichert werden."
    }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /** Register a "socket (re)connected, server reachable" callback (drives the offline-queue flush). */
    fun setWebSocketOnConnected(onConnected: (() -> Unit)?) {
        wsClient.onConnected = onConnected
    }
}
