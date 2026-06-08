package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.UpdateRecipeRequest
import com.homebase.android.data.websocket.RecipeWebSocketClient
import kotlinx.coroutines.flow.Flow

class RecipesRepository(
    private val api: HomeBaseApi,
    private val wsClient: RecipeWebSocketClient,
) {
    val incomingEvents: Flow<RecipeWebSocketClient.WsEvent> = wsClient.events

    suspend fun getRecipes(category: String? = null): Result<List<RecipeDto>> =
        runCatching { api.getRecipes(category?.takeIf { it.isNotBlank() }) }

    suspend fun getRecipe(id: String, servings: Int? = null): Result<RecipeDto> =
        runCatching { api.getRecipe(id, servings) }

    suspend fun createRecipe(request: CreateRecipeRequest): Result<RecipeDto> =
        runCatching { api.createRecipe(request) }

    suspend fun updateRecipe(id: String, request: UpdateRecipeRequest): Result<RecipeDto> =
        runCatching { api.updateRecipe(id, request) }

    suspend fun deleteRecipe(id: String): Result<Unit> =
        runCatching { api.deleteRecipe(id) }

    /** Download a recipe export (format "md" or "pdf") as raw bytes. */
    suspend fun exportRecipe(id: String, format: String, servings: Int? = null): Result<ByteArray> =
        runCatching { api.exportRecipe(id, format, servings).use { it.bytes() } }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()
}
