package com.homebase.android.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.BuildConfig
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.RecipeImageDto
import com.homebase.android.data.model.UpdateRecipeRequest
import com.homebase.android.data.repository.RecipesRepository
import com.homebase.android.data.websocket.RecipeWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecipesUiState(
    val recipes: List<RecipeDto> = emptyList(),
    val categoryFilter: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class RecipesViewModel(
    private val repository: RecipesRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipesUiState(isLoading = true))
    val uiState: StateFlow<RecipesUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getRecipes(_uiState.value.categoryFilter)
                .onSuccess { recipes -> _uiState.update { it.copy(recipes = recipes, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun setCategoryFilter(category: String?) {
        _uiState.update { it.copy(categoryFilter = category) }
        load()
    }

    fun saveRecipe(
        id: String?,
        request: CreateRecipeRequest,
        onSaved: (RecipeDto) -> Unit = {},
    ) {
        if (request.title.isBlank()) return
        viewModelScope.launch {
            val result = if (id == null) {
                repository.createRecipe(request)
            } else {
                repository.updateRecipe(
                    id,
                    UpdateRecipeRequest(
                        title = request.title,
                        description = request.description,
                        servings = request.servings,
                        prepTimeMinutes = request.prepTimeMinutes,
                        cookTimeMinutes = request.cookTimeMinutes,
                        category = request.category,
                        ingredients = request.ingredients,
                        steps = request.steps,
                    ),
                )
            }
            result
                .onSuccess { recipe ->
                    upsert(recipe)
                    onSaved(recipe)
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteRecipe(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteRecipe(id)
                .onSuccess {
                    _uiState.update { state -> state.copy(recipes = state.recipes.filter { it.id != id }) }
                    onDeleted()
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Fetch a recipe export (format "md" or "pdf") as bytes and hand the result to [onResult];
     * the screen turns success into a file + share-sheet (it owns the Android Context).
     */
    fun exportRecipe(
        id: String,
        format: String,
        servings: Int? = null,
        onResult: (Result<ByteArray>) -> Unit,
    ) {
        viewModelScope.launch { onResult(repository.exportRecipe(id, format, servings)) }
    }

    fun uploadImage(recipeId: String, bytes: ByteArray, filename: String, contentType: String) {
        viewModelScope.launch {
            repository.uploadImage(recipeId, bytes, filename, contentType)
                .onSuccess { recipe -> upsert(recipe) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun removeImage(recipeId: String, imageId: String) {
        viewModelScope.launch {
            repository.deleteImage(recipeId, imageId)
                .onSuccess { recipe -> upsert(recipe) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Authenticated URL for a recipe image. Coil can set neither an Authorization header nor a
     * WebSocket subprotocol, so the backend accepts the JWT via the `?token=` query param for
     * these image loads only — same fallback as the note images.
     */
    fun imageUrl(image: RecipeImageDto): String =
        BuildConfig.BASE_URL.trimEnd('/') + "/recipes/${image.recipeId}/images/${image.id}?token=$token"

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun upsert(recipe: RecipeDto) {
        _uiState.update { state ->
            val matchesFilter = state.categoryFilter == null || recipe.category == state.categoryFilter
            val exists = state.recipes.any { it.id == recipe.id }
            val recipes = when {
                exists && matchesFilter -> state.recipes.map { if (it.id == recipe.id) recipe else it }
                exists -> state.recipes.filter { it.id != recipe.id }
                matchesFilter -> listOf(recipe) + state.recipes
                else -> state.recipes
            }
            state.copy(recipes = recipes)
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is RecipeWebSocketClient.WsEvent.RecipeCreated -> upsert(event.recipe)
                    is RecipeWebSocketClient.WsEvent.RecipeUpdated -> upsert(event.recipe)
                    is RecipeWebSocketClient.WsEvent.RecipeDeleted ->
                        _uiState.update { state ->
                            state.copy(recipes = state.recipes.filter { it.id != event.recipe.id })
                        }
                }
            }
        }
    }

    /** Reconnect the channel if it dropped — called from the UI when the app returns to the foreground. */
    fun ensureConnected() = repository.ensureWebSocketConnected()

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
