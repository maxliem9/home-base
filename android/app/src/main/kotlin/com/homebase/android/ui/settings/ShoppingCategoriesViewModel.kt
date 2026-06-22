package com.homebase.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingCategoryRuleDto
import com.homebase.android.data.model.UpdateShoppingCategoryRequest
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.ui.shopping.DEFAULT_ITEM_ICON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Einkaufskategorien settings subpage (#411): the editable catalog + auto-resolve
 *  rules, kept live over the shopping WS channel. */
data class ShoppingCategoriesUiState(
    val categories: List<ShoppingCategoryDto> = emptyList(),
    val rules: List<ShoppingCategoryRuleDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Owns the Einkaufskategorien settings subpage (#411) — the Android mirror of the web's
 * `ShoppingCategoriesSettings`. Fetches the editable category catalog + the auto-resolve rules,
 * mutates them through [repository], and refetches the affected list on the shopping WS broadcasts
 * (`SHOPPING_CATEGORY_CHANGED` / `SHOPPING_CATEGORY_RULE_CHANGED`).
 *
 * The repository here is a DEDICATED instance with its own shopping WebSocket (see AppContainer), so
 * its event collection + connect/disconnect lifecycle don't collide with the shopping screen's VM.
 */
class ShoppingCategoriesViewModel(
    private val repository: ShoppingRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShoppingCategoriesUiState())
    val uiState: StateFlow<ShoppingCategoriesUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    /** Fetch both lists; flips [ShoppingCategoriesUiState.loading] off once the first pass settles. */
    fun load() {
        viewModelScope.launch {
            fetchCategories()
            fetchRules()
            _uiState.update { it.copy(loading = false) }
        }
    }

    private suspend fun fetchCategories() {
        repository.getCategories()
            .onSuccess { cats -> _uiState.update { it.copy(categories = cats) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
    }

    private suspend fun fetchRules() {
        repository.getCategoryRules()
            .onSuccess { rules -> _uiState.update { it.copy(rules = rules) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
    }

    // --- Categories: add / rename / change emoji / reorder / delete -----------------------------

    /**
     * Create or update a category (PUT when [key] is set, POST otherwise). Mirrors the web save:
     * a blank label no-ops; a blank emoji defaults to the cart icon (web parity) so it can't 400.
     */
    fun saveCategory(key: String?, label: String, emoji: String) {
        if (label.isBlank()) return
        val safeEmoji = emoji.trim().ifBlank { DEFAULT_ITEM_ICON }
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            val result = if (key != null) {
                repository.updateCategory(key, UpdateShoppingCategoryRequest(label = label.trim(), emoji = safeEmoji))
            } else {
                repository.createCategory(label = label.trim(), emoji = safeEmoji)
            }
            result
                .onSuccess { fetchCategories() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Delete a category; OTHER is protected (its delete is hidden in the UI, backstopped server-side). */
    fun deleteCategory(key: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            repository.deleteCategory(key)
                .onSuccess { fetchCategories() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Reorder by swapping a category's sortOrder with its neighbour's (PUT both), mirroring the web.
     * The refetch resorts the list, so no optimistic reshuffle is needed. [index]+[dir] must be in range.
     */
    fun moveCategory(index: Int, dir: Int) {
        val cats = _uiState.value.categories
        val a = cats.getOrNull(index) ?: return
        val b = cats.getOrNull(index + dir) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            val ra = repository.updateCategory(a.key, UpdateShoppingCategoryRequest(sortOrder = b.sortOrder))
            val rb = repository.updateCategory(b.key, UpdateShoppingCategoryRequest(sortOrder = a.sortOrder))
            val error = ra.exceptionOrNull()?.message ?: rb.exceptionOrNull()?.message
            if (error != null) _uiState.update { it.copy(error = error) }
            fetchCategories()
        }
    }

    // --- Rules: add / edit / delete ------------------------------------------------------------

    /**
     * Upsert a rule (PUT, keyed by the normalized displayName). When an edit RENAMES the rule, the
     * upsert mints a new keyed entry, so the old one is deleted afterwards (mirrors the web). [icon]
     * blank = keep/default per the backend contract.
     */
    fun saveRule(displayName: String, category: String, icon: String, editingName: String? = null) {
        if (displayName.isBlank() || category.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            repository.upsertCategoryRule(displayName = displayName.trim(), category = category, icon = icon.trim().ifBlank { null })
                .onSuccess {
                    // A rename created a new keyed entry — remove the stale one (normalized compare).
                    val from = editingName?.trim()
                    if (from != null && from.lowercase() != displayName.trim().lowercase()) {
                        repository.deleteCategoryRule(from)
                    }
                    fetchRules()
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteRule(displayName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            repository.deleteCategoryRule(displayName)
                .onSuccess { fetchRules() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    // Refetch the affected list on its broadcast (web parity). Item/list/template
                    // events ride the same channel but don't concern this subpage.
                    is ShoppingWebSocketClient.WsEvent.CategoryChanged -> fetchCategories()
                    is ShoppingWebSocketClient.WsEvent.CategoryRuleChanged -> fetchRules()
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
