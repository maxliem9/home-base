package com.homebase.android.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShoppingUiState(
    val lists: List<ShoppingListDto> = emptyList(),
    val items: List<ShoppingItemDto> = emptyList(),
    val activeListId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val activeList: ShoppingListDto? get() = lists.firstOrNull { it.id == activeListId } ?: lists.firstOrNull()

    val activeIsFirst: Boolean get() = activeList != null && lists.firstOrNull()?.id == activeList?.id

    /** Items in the active list; the first list also surfaces unfiled (null) items. */
    val visibleItems: List<ShoppingItemDto>
        get() {
            val id = activeList?.id ?: return items.filter { it.listId == null }
            return items.filter { it.listId == id || (activeIsFirst && it.listId == null) }
        }

    /** Count of open (unchecked) items across all lists — used for the drawer badge. */
    val openCount: Int get() = items.count { !it.checked }
}

class ShoppingViewModel(
    private val repository: ShoppingRepository,
    private val token: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShoppingUiState(isLoading = true))
    val uiState: StateFlow<ShoppingUiState> = _uiState.asStateFlow()

    init {
        load()
        observeWebSocket()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val lists = repository.getLists()
            val items = repository.getItems()
            val error = lists.exceptionOrNull()?.message ?: items.exceptionOrNull()?.message
            _uiState.update { state ->
                state.copy(
                    lists = lists.getOrDefault(state.lists),
                    items = items.getOrDefault(state.items),
                    isLoading = false,
                    error = error,
                )
            }
        }
    }

    fun selectList(id: String?) = _uiState.update { it.copy(activeListId = id) }

    fun addItem(name: String) {
        if (name.isBlank()) return
        val listId = _uiState.value.activeList?.id
        viewModelScope.launch {
            repository.createItem(name.trim(), listId)
                .onSuccess { upsertItem(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun toggleChecked(item: ShoppingItemDto) {
        viewModelScope.launch {
            repository.updateItem(item.id, UpdateShoppingItemRequest(checked = !item.checked))
                .onSuccess { upsertItem(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            repository.deleteItem(id)
                .onSuccess { _uiState.update { s -> s.copy(items = s.items.filter { it.id != id }) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Remove all checked ("Im Wagen") items from the active list. */
    fun clearChecked() {
        val checked = _uiState.value.visibleItems.filter { it.checked }
        checked.forEach { deleteItem(it.id) }
    }

    /**
     * Push the chosen (already serving-scaled) recipe ingredients onto [listId] via the batch
     * endpoint, which formats each as a "200 g Mehl" label and merges quantities into matching
     * items already on the list. Reports how many were freshly added vs. merged via [onResult].
     */
    fun addIngredients(
        listId: String?,
        lines: List<ShoppingLineInput>,
        onResult: (added: Int, merged: Int) -> Unit = { _, _ -> },
    ) {
        if (lines.isEmpty()) {
            onResult(0, 0)
            return
        }
        val targetId = listId ?: _uiState.value.activeList?.id
        viewModelScope.launch {
            repository.batchAdd(targetId, lines)
                .onSuccess { resp ->
                    resp.items.forEach { upsertItem(it) }
                    onResult(resp.added, resp.merged)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                    onResult(0, 0)
                }
        }
    }

    fun createList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createList(name.trim())
                .onSuccess { list ->
                    _uiState.update { s ->
                        val lists = if (s.lists.any { it.id == list.id }) s.lists else s.lists + list
                        s.copy(lists = lists, activeListId = list.id)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun upsertItem(item: ShoppingItemDto) {
        _uiState.update { s ->
            val items = if (s.items.any { it.id == item.id }) {
                s.items.map { if (it.id == item.id) item else it }
            } else {
                listOf(item) + s.items
            }
            s.copy(items = items)
        }
    }

    private fun upsertList(list: ShoppingListDto) {
        _uiState.update { s ->
            val lists = if (s.lists.any { it.id == list.id }) {
                s.lists.map { if (it.id == list.id) list else it }
            } else {
                s.lists + list
            }
            s.copy(lists = lists)
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is ShoppingWebSocketClient.WsEvent.ItemCreated -> upsertItem(event.item)
                    is ShoppingWebSocketClient.WsEvent.ItemUpdated -> upsertItem(event.item)
                    is ShoppingWebSocketClient.WsEvent.ItemDeleted ->
                        _uiState.update { s -> s.copy(items = s.items.filter { it.id != event.item.id }) }
                    is ShoppingWebSocketClient.WsEvent.ListCreated -> upsertList(event.list)
                    is ShoppingWebSocketClient.WsEvent.ListUpdated -> upsertList(event.list)
                    is ShoppingWebSocketClient.WsEvent.ListDeleted ->
                        _uiState.update { s -> s.copy(lists = s.lists.filter { it.id != event.list.id }) }
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
