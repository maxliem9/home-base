package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.UpdateRecipeRequest
import com.homebase.android.data.websocket.RecipeWebSocketClient
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class RecipesRepository(
    private val api: HomeBaseApi,
    private val wsClient: RecipeWebSocketClient,
) {
    val incomingEvents: Flow<RecipeWebSocketClient.WsEvent> = wsClient.events

    suspend fun getRecipes(category: String? = null): Result<List<RecipeDto>> =
        apiCatching { api.getRecipes(category?.takeIf { it.isNotBlank() }) }

    suspend fun getRecipe(id: String, servings: Int? = null): Result<RecipeDto> =
        apiCatching { api.getRecipe(id, servings) }

    suspend fun createRecipe(request: CreateRecipeRequest): Result<RecipeDto> =
        apiCatching { api.createRecipe(request) }

    suspend fun updateRecipe(id: String, request: UpdateRecipeRequest): Result<RecipeDto> =
        apiCatching { api.updateRecipe(id, request) }

    suspend fun deleteRecipe(id: String): Result<Unit> =
        apiCatching { api.deleteRecipe(id) }

    suspend fun uploadImage(
        recipeId: String,
        bytes: ByteArray,
        filename: String,
        contentType: String,
    ): Result<RecipeDto> = apiCatching {
        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = filename,
            body = bytes.toRequestBody(contentType.toMediaTypeOrNull()),
        )
        api.uploadRecipeImage(recipeId, part)
    }

    suspend fun deleteImage(recipeId: String, imageId: String): Result<RecipeDto> =
        apiCatching { api.deleteRecipeImage(recipeId, imageId) }

    /** Download a recipe export (format "md" or "pdf") as raw bytes. */
    suspend fun exportRecipe(id: String, format: String, servings: Int? = null): Result<ByteArray> =
        apiCatching { api.exportRecipe(id, format, servings).use { it.bytes() } }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()
}
