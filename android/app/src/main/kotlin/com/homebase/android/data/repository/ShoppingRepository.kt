package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.BatchAddShoppingRequest
import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.CreateShoppingCategoryRequest
import com.homebase.android.data.model.CreateShoppingItemRequest
import com.homebase.android.data.model.CreateShoppingListRequest
import com.homebase.android.data.model.CreateShoppingTemplateRequest
import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingCategoryRuleDto
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.ShoppingSuggestion
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.data.model.TemplateItemInput
import com.homebase.android.data.model.UpdateShoppingCategoryRequest
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.model.UpdateShoppingListRequest
import com.homebase.android.data.model.UpdateShoppingTemplateRequest
import com.homebase.android.data.model.UpsertCategoryRuleRequest
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

    /** "Most used" autocomplete source (#389): catalog baseline + the household's usage tally. Scoped
     *  to [listId]'s category set when given (#412; the suggestion names stay household-global). */
    suspend fun getSuggestions(listId: String? = null): Result<List<ShoppingSuggestion>> =
        apiCatching { api.getShoppingSuggestions(listId) }

    suspend fun createItem(name: String, listId: String?, quantity: String? = null): Result<ShoppingItemDto> =
        apiCatching { api.createShoppingItem(CreateShoppingItemRequest(name, listId, quantity)) }

    suspend fun batchAdd(listId: String?, lines: List<ShoppingLineInput>): Result<BatchAddShoppingResponse> =
        apiCatching { api.batchAddShoppingItems(BatchAddShoppingRequest(listId, lines)) }

    suspend fun updateItem(id: String, request: UpdateShoppingItemRequest): Result<ShoppingItemDto> =
        apiCatching { api.updateShoppingItem(id, request) }

    suspend fun deleteItem(id: String): Result<Unit> =
        apiCatching { api.deleteShoppingItem(id) }

    // --- Lists ---

    suspend fun getLists(): Result<List<ShoppingListDto>> = apiCatching { api.getShoppingLists() }

    suspend fun createList(name: String, ownCategories: Boolean = false): Result<ShoppingListDto> =
        apiCatching { api.createShoppingList(CreateShoppingListRequest(name, ownCategories)) }

    suspend fun updateList(id: String, request: UpdateShoppingListRequest): Result<ShoppingListDto> =
        apiCatching { api.updateShoppingList(id, request) }

    suspend fun deleteList(id: String): Result<Unit> = apiCatching { api.deleteShoppingList(id) }

    // --- Templates (named standard lists, #215) ---

    suspend fun getTemplates(): Result<List<ShoppingTemplateDto>> = apiCatching { api.getShoppingTemplates() }

    suspend fun createTemplate(name: String, itemNames: List<String>): Result<ShoppingTemplateDto> =
        apiCatching(mapHttpError = ::templateError) {
            api.createShoppingTemplate(CreateShoppingTemplateRequest(name.trim(), itemNames.toInputs()))
        }

    /** Rename and replace the item set wholesale (web/recipe parity); both fields always sent. */
    suspend fun updateTemplate(id: String, name: String, itemNames: List<String>): Result<ShoppingTemplateDto> =
        apiCatching(mapHttpError = ::templateError) {
            api.updateShoppingTemplate(id, UpdateShoppingTemplateRequest(name.trim(), itemNames.toInputs()))
        }

    suspend fun deleteTemplate(id: String): Result<Unit> =
        apiCatching(mapHttpError = ::templateError) { api.deleteShoppingTemplate(id) }

    /** Drop blank names (a template item is just a name; an empty one is meaningless — backend does the same). */
    private fun List<String>.toInputs(): List<TemplateItemInput> =
        mapNotNull { it.trim().ifBlank { null } }.map { TemplateItemInput(it) }

    /** Map a failed template create/update/delete to German text via its ErrorResponse.code. */
    private fun templateError(e: HttpException): AppError = when (errorCodeOf(e)) {
        "INVALID_TEMPLATE" -> AppError.NAME_REQUIRED
        "NOT_FOUND" -> AppError.TEMPLATE_NOT_FOUND
        else -> AppError.TEMPLATE_SAVE_FAILED
    }

    // --- Categories (editable catalog, #411) ---

    /** [listId] (#412): the effective set for a list (own categories + shared OTHER) when given, else
     *  the shared household catalog. */
    suspend fun getCategories(listId: String? = null): Result<List<ShoppingCategoryDto>> =
        apiCatching { api.getShoppingCategories(listId) }

    /** [listId] (#412): create the category in that list's own set instead of the shared catalog. */
    suspend fun createCategory(label: String, emoji: String, sortOrder: Int? = null, listId: String? = null): Result<ShoppingCategoryDto> =
        apiCatching(mapHttpError = ::categoryError) {
            api.createShoppingCategory(CreateShoppingCategoryRequest(label.trim(), emoji.trim(), sortOrder), listId)
        }

    suspend fun updateCategory(key: String, request: UpdateShoppingCategoryRequest): Result<ShoppingCategoryDto> =
        apiCatching(mapHttpError = ::categoryError) { api.updateShoppingCategory(key, request) }

    suspend fun deleteCategory(key: String): Result<Unit> =
        apiCatching(mapHttpError = ::categoryError) { api.deleteShoppingCategory(key) }

    /** Map a failed category create/update/delete to German text via its ErrorResponse.code. */
    private fun categoryError(e: HttpException): AppError = when (errorCodeOf(e)) {
        // OTHER's delete is hidden in the UI, so this is a backstop for the protected fallback.
        "CATEGORY_PROTECTED" -> AppError.CATEGORY_PROTECTED
        "INVALID_CATEGORY" -> AppError.CATEGORY_INVALID
        "NOT_FOUND" -> AppError.CATEGORY_NOT_FOUND
        else -> AppError.CATEGORY_SAVE_FAILED
    }

    // --- Category rules (auto-resolve dictionary, #411) ---

    /** [listId] (#501): the list's own private dictionary when given, else the shared household one. */
    suspend fun getCategoryRules(listId: String? = null): Result<List<ShoppingCategoryRuleDto>> =
        apiCatching { api.getShoppingCategoryRules(listId) }

    /** Upsert a rule (keyed by the normalized displayName). [icon] omitted = keep/default per backend.
     *  [listId] (#501) scopes the rule to a list's own dictionary. */
    suspend fun upsertCategoryRule(displayName: String, category: String, icon: String? = null, listId: String? = null): Result<ShoppingCategoryRuleDto> =
        apiCatching(mapHttpError = ::ruleError) {
            api.upsertShoppingCategoryRule(UpsertCategoryRuleRequest(displayName.trim(), category, icon?.trim()?.ifBlank { null }), listId)
        }

    suspend fun deleteCategoryRule(displayName: String, listId: String? = null): Result<Unit> =
        apiCatching(mapHttpError = ::ruleError) { api.deleteShoppingCategoryRule(displayName, listId) }

    /** Map a failed rule upsert/delete to German text via its ErrorResponse.code. */
    private fun ruleError(e: HttpException): AppError = when (errorCodeOf(e)) {
        "INVALID_RULE" -> AppError.RULE_INVALID
        "INVALID_CATEGORY" -> AppError.RULE_INVALID_CATEGORY
        "NOT_FOUND" -> AppError.RULE_NOT_FOUND
        else -> AppError.RULE_SAVE_FAILED
    }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /** Register a "socket (re)connected, server reachable" callback (drives the offline-queue flush). */
    fun setWebSocketOnConnected(onConnected: (() -> Unit)?) {
        wsClient.onConnected = onConnected
    }
}
