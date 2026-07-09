package com.homebase.android.data.recipes

import com.homebase.android.data.model.RecipeDto
import com.squareup.moshi.JsonClass

/**
 * The recipes screen's last-known data, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous recipes instead of an empty screen (#520,
 * rolling out the shopping read-cache #517 to the other views).
 *
 * Only the **unfiltered** recipe list is cached ([recipes]) — the one dataset the screen loads. The
 * cache is written only while no category filter is active (see `RecipesViewModel.restoreAndMirrorSnapshot`),
 * so a filtered view can never poison it with a subset.
 */
@JsonClass(generateAdapter = true)
data class RecipesSnapshot(
    val recipes: List<RecipeDto> = emptyList(),
)
