package com.homebase.android.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShoppingUiState(
    val items: List<ShoppingItemDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

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
            repository.getItems()
                .onSuccess { items ->
                    _uiState.update { it.copy(items = items, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun addItem(name: String, category: String?) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createItem(name.trim(), category?.trim()?.takeIf { it.isNotEmpty() })
                .onSuccess { item ->
                    _uiState.update { state ->
                        if (state.items.none { it.id == item.id })
                            state.copy(items = listOf(item) + state.items)
                        else state
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun toggleChecked(item: ShoppingItemDto) {
        viewModelScope.launch {
            repository.updateItem(item.id, UpdateShoppingItemRequest(checked = !item.checked))
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(items = state.items.map { if (it.id == updated.id) updated else it })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            repository.deleteItem(id)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(items = state.items.filter { it.id != id })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is ShoppingWebSocketClient.WsEvent.ItemCreated -> {
                        _uiState.update { state ->
                            if (state.items.none { it.id == event.item.id })
                                state.copy(items = listOf(event.item) + state.items)
                            else state
                        }
                    }
                    is ShoppingWebSocketClient.WsEvent.ItemUpdated -> {
                        _uiState.update { state ->
                            state.copy(
                                items = state.items.map { if (it.id == event.item.id) event.item else it }
                            )
                        }
                    }
                    is ShoppingWebSocketClient.WsEvent.ItemDeleted -> {
                        _uiState.update { state ->
                            state.copy(items = state.items.filter { it.id != event.item.id })
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }
}
