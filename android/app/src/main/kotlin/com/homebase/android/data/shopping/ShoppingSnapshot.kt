package com.homebase.android.data.shopping

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingListDto
import com.squareup.moshi.JsonClass

/**
 * The shopping screen's last-known data, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous lists + items instead of an empty screen (#517).
 *
 * Only the two fields that make the screen non-empty are cached: [lists] and [items] (exactly what the
 * user saw). The grouping catalog keeps its built-in fallback offline (grouping still works) and the
 * secondary data (templates, autocomplete suggestions) is non-essential when disconnected, so neither
 * is cached — keeps the blob small and the invalidation story simple.
 */
@JsonClass(generateAdapter = true)
data class ShoppingSnapshot(
    val lists: List<ShoppingListDto> = emptyList(),
    val items: List<ShoppingItemDto> = emptyList(),
)
