package com.homebase.android.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.BuildConfig
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.RecipeDraftDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.RecipeImageDto
import com.homebase.android.data.model.UpdateRecipeRequest
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.recipes.RecipesSnapshot
import com.homebase.android.data.repository.RecipesRepository
import com.homebase.android.data.websocket.RecipeWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /**
     * Durable "last-known recipes" cache (#520, read-side twin of the shopping cache #517). Seeded on
     * a cold start so a launch with no connection shows the previous recipes instead of nothing, and
     * mirrored on every change while no category filter is active. null in tests → no read-cache.
     */
    private val snapshotStore: SnapshotStore<RecipesSnapshot>? = null,
    // Resolves a repository AppError (carried by ApiException) to localized text via strings.xml (#558).
    // Default keeps the raw exception message (for tests); MainActivity injects the Context-backed one.
    private val errorText: (Throwable) -> String? = { it.message },
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipesUiState(isLoading = true))
    val uiState: StateFlow<RecipesUiState> = _uiState.asStateFlow()

    /** True once a fetch has successfully applied server data (#520); guards the cache seed against
     *  clobbering live data and gates the load error. Single-threaded (viewModelScope = Main). */
    private var hasServerData = false

    init {
        load()
        observeWebSocket()
        restoreAndMirrorSnapshot()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getRecipes(_uiState.value.categoryFilter)
                .onSuccess { recipes ->
                    hasServerData = true // a successful fetch landed → the cache seed must not clobber it (#520)
                    _uiState.update { it.copy(recipes = recipes, isLoading = false) }
                }
                .onFailure { e ->
                    // Keep `error` only when there is nothing to show anyway (#520): with cached/prior
                    // recipes on screen a failed refresh stays silent — offline we show the old state.
                    _uiState.update { s -> s.copy(isLoading = false, error = if (s.recipes.isEmpty()) errorText(e) else null) }
                }
        }
    }

    /**
     * Offline read-cache (#520): seed the last-known recipes from disk before starting the mirror
     * collector (so the empty startup frame can't wipe a good cache), then persist every distinct
     * change — but only while no category filter is active, so a filtered view never poisons the
     * cache with a subset. [hasServerData]/`ifEmpty` guard against clobbering fresh server data.
     */
    private fun restoreAndMirrorSnapshot() {
        val store = snapshotStore ?: return
        viewModelScope.launch {
            val cached = store.load()
            if (cached != null && !hasServerData && cached.recipes.isNotEmpty()) {
                _uiState.update { s ->
                    if (hasServerData || s.categoryFilter != null) s
                    else s.copy(recipes = s.recipes.ifEmpty { cached.recipes }, isLoading = false, error = null)
                }
            }
            uiState
                .map { it.recipes to it.categoryFilter }
                .distinctUntilChanged()
                .collect { (recipes, filter) -> if (filter == null) store.save(RecipesSnapshot(recipes = recipes)) }
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    /**
     * Import a recipe draft from a URL (#460) and hand the [Result] to [onResult]; the screen
     * turns success into a pre-filled editor and failure into an inline error message. Nothing is
     * persisted — the user reviews/saves via the normal create flow.
     */
    fun importRecipe(url: String, onResult: (Result<RecipeDraftDto>) -> Unit) {
        viewModelScope.launch { onResult(repository.importRecipe(url.trim())) }
    }

    fun deleteRecipe(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteRecipe(id)
                .onSuccess {
                    _uiState.update { state -> state.copy(recipes = state.recipes.filter { it.id != id }) }
                    onDeleted()
                }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
        }
    }

    fun removeImage(recipeId: String, imageId: String) {
        viewModelScope.launch {
            repository.deleteImage(recipeId, imageId)
                .onSuccess { recipe -> upsert(recipe) }
                .onFailure { e -> _uiState.update { it.copy(error = errorText(e)) } }
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
