package com.homebase.android.data.wochenplan

import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.ShoppingListDto
import com.squareup.moshi.JsonClass

/**
 * The Wochenplan's last-known data, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous plan instead of an empty grid (#520, rolling
 * out the shopping read-cache #517 to the other views).
 *
 * The entries are **week-scoped**, so [weekStart] (ISO date of the visible Monday) is cached with them:
 * on restore the entries are only seeded when the cached week equals the currently-visible week (see
 * `MealPlanViewModel.restoreAndMirrorSnapshot`). [recipes] and [shoppingLists] are week-independent, so
 * they seed freely (they drive the picker and the "add to list" action).
 */
@JsonClass(generateAdapter = true)
data class MealPlanSnapshot(
    val weekStart: String = "",
    val entries: List<MealPlanEntryDto> = emptyList(),
    val recipes: List<RecipeDto> = emptyList(),
    val shoppingLists: List<ShoppingListDto> = emptyList(),
)
